"""One explicit upload, with absolute pacing and bounded transport waits."""

import asyncio
import logging
import ssl
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from uuid import UUID

from websockets.asyncio.client import ClientConnection, connect
from websockets.exceptions import InvalidStatus, WebSocketException

from .config import read_token, tls_context, validate_url
from .errors import InputError, SenderError
from .protocol import END, FRAME_PERIOD_NS, START, STOP, Ready, Stopped, pack_frame, parse_control
from .sources import FrameSource

SEND_TIMEOUT = 0.250
READY_TIMEOUT = 5.0
DRAIN_TIMEOUT = 3.0
CLOSE_TIMEOUT = 0.250
LIVE_MAX_READ_AGE_NS = 200_000_000

# This private logger cannot inherit an application's DEBUG handler and print
# Authorization headers, arbitrary remote close reasons, or exception contents.
_WIRE_LOGGER = logging.Logger("svc_broadcast_sender.wire")
_WIRE_LOGGER.disabled = True


class _DirectConnection(ClientConnection):
    async def handshake(self, *args, **kwargs):
        try:
            await super().handshake(*args, **kwargs)
        except InvalidStatus as error:
            if 300 <= error.response.status_code < 400:
                # websockets follows redirects for InvalidStatus. Replace it
                # before connect can resend our header to a different endpoint.
                raise SenderError("redirect_refused", "Endpoint redirects are not accepted.", 3) from None
            raise


class Clock(Protocol):
    def now_ns(self) -> int: ...

    async def sleep_until(self, deadline_ns: int) -> None: ...


class MonotonicClock:
    def now_ns(self) -> int:
        return time.monotonic_ns()

    async def sleep_until(self, deadline_ns: int) -> None:
        await asyncio.sleep(max(0, deadline_ns - self.now_ns()) / 1_000_000_000)


@dataclass(frozen=True)
class UploadResult:
    sent_frames: int
    dropped_frames: int


async def stream_audio(
    source: FrameSource,
    session_id: UUID,
    send: Callable[[bytes], Awaitable[None]],
    *,
    clock: Clock | None = None,
) -> UploadResult:
    """Produce finite source-time frames or fresh live frames without catch-up bursts.

    ``send`` must enforce its own transport deadline; upload() supplies the
    250 ms bound. Indexed source reads run serially off the event-loop thread.
    The clock injection supports deterministic pause and stale-read tests.
    """
    frame_count = source.frame_count
    if frame_count is not None and (type(frame_count) is not int or frame_count <= 0):
        raise SenderError("source_duration", "Source must contain at least one frame.", 2)
    clock = clock if clock is not None else MonotonicClock()
    first = await asyncio.to_thread(source.read_frame, 0)
    epoch = clock.now_ns()
    # Establish seq0 before any elapsed-frame skips: the receiver requires it.
    await send(pack_frame(session_id, 0, first))
    if frame_count is None:
        return await _stream_live(source, session_id, send, clock, epoch)
    sent, dropped, sequence = 1, 0, 1
    while frame_count is None or sequence < frame_count:
        current = max(sequence, (clock.now_ns() - epoch) // FRAME_PERIOD_NS)
        if frame_count is not None:
            current = min(current, frame_count)
        dropped += current - sequence
        sequence = current
        if frame_count is not None and sequence == frame_count:
            break
        await clock.sleep_until(epoch + sequence * FRAME_PERIOD_NS)
        if (clock.now_ns() - epoch) // FRAME_PERIOD_NS > sequence:
            continue
        pcm = await asyncio.to_thread(source.read_frame, sequence)
        if (clock.now_ns() - epoch) // FRAME_PERIOD_NS > sequence:
            # A slow read must not turn an otherwise fresh send into stale audio.
            continue
        await send(pack_frame(session_id, sequence, pcm))
        sent += 1
        sequence += 1
    return UploadResult(sent, dropped)


async def _stream_live(source, session_id, send, clock, epoch):
    """Keep device reads independent of the wire clock.

    LiveSource owns sample-age checks and bounded capture queues. A callback
    arriving just after a wire tick is still fresh: dropping it and requesting
    the next wall-clock index can chase the device forever without sending.
    Read sequentially, label accepted frames with the current wire tick, and
    skip missed wire ticks rather than replaying them. Absolute deadlines avoid
    accumulating scheduler oversleep as a permanent device/transport drift.
    """
    source_index, wire_sequence = 1, 0
    while True:
        read_started = clock.now_ns()
        pcm = await asyncio.to_thread(source.read_frame, source_index)
        source_index += 1
        next_tick = max(wire_sequence + 1, (clock.now_ns() - epoch) // FRAME_PERIOD_NS)
        await clock.sleep_until(epoch + next_tick * FRAME_PERIOD_NS)
        now = clock.now_ns()
        if now - read_started >= LIVE_MAX_READ_AGE_NS:
            # A scheduler stall can age even a frame that was fresh in the reader.
            # Discard it; the next read obtains fresh data from the bounded source.
            continue
        wire_sequence = max(wire_sequence + 1, (now - epoch) // FRAME_PERIOD_NS)
        await send(pack_frame(session_id, wire_sequence, pcm))


async def _bounded_send(connection: ClientConnection, message: str | bytes) -> None:
    try:
        async with asyncio.timeout(SEND_TIMEOUT):
            await connection.send(message)
    except TimeoutError:
        connection.transport.abort()
        raise SenderError("send_timeout", "Upload stalled for 250 ms; the session was aborted.", 5) from None
    except asyncio.CancelledError:
        # Cancelled sends can have bytes queued. Abort instead of replaying them.
        connection.transport.abort()
        raise


async def _receive_stop(connection: ClientConnection) -> Stopped:
    control = parse_control(await connection.recv())
    if not isinstance(control, Stopped):
        raise SenderError("unexpected_control", "Server sent ready more than once.")
    return control


def _server_stopped() -> SenderError:
    return SenderError("server_stopped", "Server stopped the stream before completion.")


async def _best_effort_stop(connection: ClientConnection) -> None:
    try:
        await _bounded_send(connection, STOP)
    except (SenderError, WebSocketException, OSError):
        pass


async def _close(connection: ClientConnection) -> None:
    try:
        async with asyncio.timeout(CLOSE_TIMEOUT):
            await connection.close()
    except (TimeoutError, WebSocketException, OSError):
        connection.transport.abort()
    except asyncio.CancelledError:
        connection.transport.abort()
        raise


async def run_session(connection: ClientConnection, source: FrameSource) -> UploadResult:
    """Run v1 controls on one already-authenticated connection; caller closes it."""
    receiver = producer = None
    completed = False
    try:
        await _bounded_send(connection, START)
        try:
            async with asyncio.timeout(READY_TIMEOUT):
                ready = parse_control(await connection.recv())
        except TimeoutError:
            raise SenderError("ready_timeout", "Server did not become ready within 5 seconds.", 5) from None
        if not isinstance(ready, Ready):
            raise _server_stopped()
        receiver = asyncio.create_task(_receive_stop(connection))
        producer = asyncio.create_task(
            stream_audio(source, ready.session_id, lambda frame: _bounded_send(connection, frame))
        )
        done, _ = await asyncio.wait((receiver, producer), return_when=asyncio.FIRST_COMPLETED)
        if receiver in done:
            receiver.result()  # Propagate a safe protocol/server error, if present.
            raise _server_stopped()
        result = producer.result()
        await _bounded_send(connection, END)
        try:
            async with asyncio.timeout(DRAIN_TIMEOUT):
                stopped = await receiver
        except TimeoutError:
            raise SenderError("drain_timeout", "Server did not confirm completion within 3 seconds.", 5) from None
        if stopped.reason != "completed":
            raise _server_stopped()
        completed = True
        return result
    finally:
        tasks = [task for task in (receiver, producer) if task is not None]
        for task in tasks:
            if not task.done():
                task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if not completed:
            await _best_effort_stop(connection)


async def upload(
    source: FrameSource,
    *,
    url: str,
    token_file: str | Path | None = None,
    ca_file: str | Path | None = None,
    profile: dict | None = None,
) -> UploadResult:
    """Authenticate once, wait ready, stream, end/drain, then close. Never retry.

    Cancellation stops/discards the session and propagates CancelledError. The
    caller retains ownership of the source; only PCM frames enter this transport.
    """
    url = validate_url(url)
    if profile is None:
        context = tls_context(url, ca_file)
        token = read_token(token_file)
    else:
        from .profiles import validate_profile, profile_context
        validate_profile(profile)
        if token_file is not None or ca_file is not None or url != profile["url"]:
            raise InputError("profile_override", "Saved profile endpoints and credentials cannot be overridden.")
        context = profile_context(profile["ca_pem"])
        token = profile["token"]
    connection = None
    try:
        options = {"ssl": context} if context is not None else {}
        connection = await connect(
            url,
            additional_headers={"Authorization": f"Bearer {token}"},
            create_connection=_DirectConnection,
            proxy=None,
            compression=None,
            open_timeout=5,
            close_timeout=CLOSE_TIMEOUT,
            max_size=1024,
            max_queue=4,
            write_limit=1944 * 2,
            ping_interval=20,
            ping_timeout=20,
            logger=_WIRE_LOGGER,
            **options,
        )
        return await run_session(connection, source)
    except SenderError:
        raise
    except ssl.SSLCertVerificationError:
        raise SenderError("tls_verification", "TLS certificate verification failed.", 3) from None
    except InvalidStatus as error:
        if error.response.status_code in (401, 403):
            raise SenderError("authentication", "Server rejected authentication; check authorization, online policy and the selected profile or token file.", 3) from None
        raise SenderError("handshake", "Server rejected the WebSocket connection.", 3) from None
    except (OSError, WebSocketException, TimeoutError, ValueError):
        raise SenderError("connection", "Connection failed or closed before completion.", 3) from None
    finally:
        if connection is not None:
            await _close(connection)
