import asyncio
import threading
from types import SimpleNamespace

import pytest
from websockets.asyncio.server import serve

from svc_broadcast_sender.cli import _capture_upload
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.protocol import PCM_BYTES


@pytest.mark.parametrize("termination", ["cancel", "server_stop"])
def test_capture_owner_unblocks_reader_before_async_executor_shutdown(tmp_path, termination):
    class WaitingSource:
        frame_count = None

        def __init__(self):
            self.entered = threading.Event()
            self.stopped = threading.Event()
            self.exited = threading.Event()

        def read_frame(self, sequence):
            self.entered.set()
            try:
                self.stopped.wait(3)
                return bytes(PCM_BYTES)
            finally:
                self.exited.set()

        def close(self):
            self.stopped.set()

        def stats(self):
            return dict(peak_dbfs=float("-inf"), rms_dbfs=float("-inf"),
                        clipped_samples=0, dropped_frames=0, queued_frames=0)

    async def scenario():
        source = WaitingSource()
        stop_server = asyncio.Event()
        token = tmp_path / "token"
        token.write_text("c" * 64)

        async def handler(socket):
            await socket.recv()
            await socket.send('{"type":"ready","version":1,"session_id":"00112233-4455-6677-8899-aabbccddeeff","frame_samples":960,"max_buffer_frames":10}')
            if termination == "server_stop":
                await stop_server.wait()
                await socket.send('{"type":"stopped","reason":"stopped"}')
            await socket.wait_closed()

        async with serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            args = SimpleNamespace(url=f"ws://127.0.0.1:{port}/v1/audio", token_file=token, ca_file=None)
            task = asyncio.create_task(_capture_upload(source, args))
            try:
                assert await asyncio.to_thread(source.entered.wait, 2)
                if termination == "cancel":
                    task.cancel()
                    with pytest.raises(asyncio.CancelledError):
                        await asyncio.wait_for(task, 1)
                else:
                    stop_server.set()
                    with pytest.raises(SenderError) as error:
                        await asyncio.wait_for(task, 1)
                    assert error.value.code == "server_stopped"
                assert source.stopped.is_set()
                assert await asyncio.to_thread(source.exited.wait, 0.5)
            finally:
                source.close()
                stop_server.set()
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    asyncio.run(scenario())
