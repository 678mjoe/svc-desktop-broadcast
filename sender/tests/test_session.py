"""Local socket state-machine tests; these do not claim SVC playback coverage."""

import asyncio
import json
from http import HTTPStatus

import pytest
from websockets.asyncio.server import serve
from websockets.datastructures import Headers
from websockets.http11 import Response

from svc_broadcast_sender import transport
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.protocol import END, PCM_BYTES, STOP
from svc_broadcast_sender.transport import upload

READY = json.dumps({"type": "ready", "version": 1, "session_id": "00112233-4455-6677-8899-aabbccddeeff", "frame_samples": 960, "max_buffer_frames": 10})
COMPLETED = '{"type":"stopped","reason":"completed"}'


class Source:
    def __init__(self, count=1):
        self.frame_count = count
        self.reads = []

    def read_frame(self, sequence):
        self.reads.append(sequence)
        return bytes(PCM_BYTES)


@pytest.fixture
def token_file(tmp_path):
    path = tmp_path / "token"
    path.write_text("c" * 64 + "\n")
    return path


def endpoint(server):
    return f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/v1/audio"


def test_source_waits_for_ready_and_completion_waits_for_drain(token_file):
    async def scenario():
        started, allow_ready = asyncio.Event(), asyncio.Event()
        ended, allow_finish = asyncio.Event(), asyncio.Event()
        received = []

        async def handler(connection):
            received.append(await connection.recv())
            started.set()
            await allow_ready.wait()
            await connection.send(READY)
            received.append(await connection.recv())
            received.append(await connection.recv())
            ended.set()
            await allow_finish.wait()
            await connection.send(COMPLETED)
            await connection.close()

        source = Source()
        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, compression=None) as server:
            sending = asyncio.create_task(upload(source, url=endpoint(server), token_file=token_file))
            await started.wait()
            assert source.reads == []
            assert not sending.done()
            allow_ready.set()
            await ended.wait()
            assert received[-1] == END
            assert len(received[1]) == 1944
            assert not sending.done()
            allow_finish.set()
            result = await sending
            assert result.sent_frames == 1
            assert result.dropped_frames == 0
            assert source.reads == [0]

    asyncio.run(scenario())


@pytest.mark.parametrize("message,code", [
    ('{"type":"stopped","reason":"admin_stop"}', "server_stopped"),
    (json.dumps({"type": "error", "code": "c" * 64}), "server_error"),
    (COMPLETED, "server_stopped"),
])
def test_server_control_interrupts_active_source_without_end(token_file, message, code):
    async def scenario():
        controls = []

        async def handler(connection):
            await connection.recv()
            await connection.send(READY)
            await connection.recv()
            await connection.send(message)
            async for item in connection:
                if isinstance(item, str):
                    controls.append(item)

        source = Source(10_000)
        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, compression=None) as server:
            with pytest.raises(SenderError) as error:
                await upload(source, url=endpoint(server), token_file=token_file)
            assert error.value.code == code
            assert error.value.exit_code == 4
            assert "c" * 64 not in str(error.value)
        assert 1 <= len(source.reads) < source.frame_count
        assert END not in controls

    asyncio.run(scenario())


def test_cancellation_before_ready_sends_stop_and_never_reads_audio(token_file):
    async def scenario():
        started = asyncio.Event()
        controls = []

        async def handler(connection):
            controls.append(await connection.recv())
            started.set()
            async for message in connection:
                controls.append(message)

        source = Source()
        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, compression=None) as server:
            sending = asyncio.create_task(upload(source, url=endpoint(server), token_file=token_file))
            await started.wait()
            sending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await sending
        assert source.reads == []
        assert STOP in controls
        assert END not in controls

    asyncio.run(scenario())


def test_ready_deadline_stops_session_without_reading_source(token_file, monkeypatch):
    monkeypatch.setattr(transport, "READY_TIMEOUT", 0)

    async def scenario():
        controls = []

        async def handler(connection):
            async for message in connection:
                controls.append(message)

        source = Source()
        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, compression=None) as server:
            with pytest.raises(SenderError) as error:
                await upload(source, url=endpoint(server), token_file=token_file)
            assert error.value.code == "ready_timeout"
            assert error.value.exit_code == 5
        assert source.reads == []
        assert STOP in controls
        assert END not in controls

    asyncio.run(scenario())


def test_missing_completion_has_bounded_drain_and_stop(token_file, monkeypatch):
    monkeypatch.setattr(transport, "DRAIN_TIMEOUT", 0)

    async def scenario():
        controls = []

        async def handler(connection):
            await connection.recv()
            await connection.send(READY)
            async for message in connection:
                if isinstance(message, str):
                    controls.append(message)

        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, compression=None) as server:
            with pytest.raises(SenderError) as error:
                await upload(Source(), url=endpoint(server), token_file=token_file)
            assert error.value.code == "drain_timeout"
        assert controls == [END, STOP]

    asyncio.run(scenario())


def test_authentication_failure_is_safe_and_is_not_retried(token_file):
    async def scenario():
        attempts = []
        secret = "c" * 64

        def reject(connection, request):
            attempts.append(1)
            return connection.respond(HTTPStatus.UNAUTHORIZED, secret)

        async def handler(connection):
            raise AssertionError("HTTP rejection must happen before upgrade")

        source = Source()
        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, process_request=reject) as server:
            with pytest.raises(SenderError) as error:
                await upload(source, url=endpoint(server), token_file=token_file)
            assert error.value.code == "authentication"
            assert error.value.exit_code == 3
            assert secret not in str(error.value)
        assert attempts == [1]
        assert source.reads == []

    asyncio.run(scenario())


def test_redirect_never_forwards_token_to_another_endpoint(token_file):
    async def scenario():
        attempts, redirected_requests = [], []

        async def handler(connection):
            await connection.wait_closed()

        def observe(connection, request):
            redirected_requests.append(request)

        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0, process_request=observe) as destination:
            def redirect(connection, request):
                attempts.append(1)
                return Response(302, "Found", Headers({"Location": endpoint(destination)}), b"")

            async with serve(handler, "127.0.0.1", 0, process_request=redirect) as server:
                with pytest.raises(SenderError) as error:
                    await upload(Source(), url=endpoint(server), token_file=token_file)
                assert error.value.code == "redirect_refused"
            assert redirected_requests == []
            assert attempts == [1]

    asyncio.run(scenario())
