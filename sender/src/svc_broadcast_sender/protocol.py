"""Fixed PCM wire format and strict server-control parsing."""

import json
import struct
from dataclasses import dataclass
from uuid import UUID

from .errors import SenderError

SAMPLE_RATE = 48_000
FRAME_SAMPLES = 960
PCM_BYTES = FRAME_SAMPLES * 2
FRAME_PERIOD_NS = 20_000_000
MAX_SEQUENCE = (1 << 63) - 1
MAX_CONTROL_BYTES = 1024
START = json.dumps(
    {
        "type": "start",
        "version": 1,
        "sample_rate": SAMPLE_RATE,
        "channels": 1,
        "encoding": "pcm_s16le",
        "frame_samples": FRAME_SAMPLES,
    },
    separators=(",", ":"),
)
END = '{"type":"end"}'
STOP = '{"type":"stop"}'

# Only local, fixed messages may be logged. Never echo arbitrary remote values.
_SERVER_ERRORS = {
    "rate_exceeded": "Server rejected the stream: audio arrival rate exceeded the limit.",
    "future_sequence": "Server rejected the stream: audio sequence is ahead of the server clock.",
    "invalid_sequence": "Server rejected the stream: audio sequence is invalid or repeated.",
    "invalid_session": "Server rejected the stream: audio session does not match.",
    "invalid_size": "Server rejected the stream: audio frame size is invalid.",
    "stream_ended": "Server rejected the stream: this audio session has already ended.",
    "backpressure": "Server rejected the stream: server output is blocked.",
    "busy": "Server rejected the stream: another broadcast is active.",
}


@dataclass(frozen=True)
class Ready:
    session_id: UUID


@dataclass(frozen=True)
class Stopped:
    reason: str


def _invalid_control() -> SenderError:
    return SenderError("invalid_control", "Server sent an invalid control message.")


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise _invalid_control()
        result[key] = value
    return result


def parse_control(message: str | bytes) -> Ready | Stopped:
    if not isinstance(message, str):
        raise _invalid_control()
    try:
        if len(message.encode("utf-8")) > MAX_CONTROL_BYTES:
            raise _invalid_control()
        data = json.loads(message, object_pairs_hook=_unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise _invalid_control() from None
    if not isinstance(data, dict):
        raise _invalid_control()
    kind = data.get("type")
    if kind == "ready":
        for key, expected in (("version", 1), ("frame_samples", 960), ("max_buffer_frames", 10)):
            if type(data.get(key)) is not int or data[key] != expected:
                raise _invalid_control()
        session = data.get("session_id")
        if not isinstance(session, str):
            raise _invalid_control()
        try:
            session_id = UUID(session)
        except ValueError:
            raise _invalid_control() from None
        if str(session_id) != session:
            raise _invalid_control()
        return Ready(session_id)
    if kind == "stopped":
        reason = data.get("reason")
        if not isinstance(reason, str) or not 1 <= len(reason) <= 64:
            raise _invalid_control()
        return Stopped(reason)
    if kind == "error":
        code = data.get("code")
        if not isinstance(code, str) or not 1 <= len(code) <= 64:
            raise _invalid_control()
        # The remote peer can echo credentials in arbitrary fields, including code.
        raise SenderError("server_error", _SERVER_ERRORS.get(code, "Server rejected the stream."))
    raise _invalid_control()


def pack_frame(session_id: UUID, sequence: int, pcm: bytes) -> bytes:
    if type(sequence) is not int or not 0 <= sequence <= MAX_SEQUENCE:
        raise SenderError("source_sequence", "Source frame index exceeds the protocol range.")
    if not isinstance(pcm, bytes) or len(pcm) != PCM_BYTES:
        raise SenderError("source_frame", "Source must provide exactly 960 mono PCM16 samples.")
    return session_id.bytes + struct.pack(">Q", sequence) + pcm
