"""Exercise the Mac float pipe and real transport without opening a native tap."""

import asyncio
import json
import sys

import numpy as np
import pytest
from websockets.asyncio.server import serve

from svc_broadcast_sender import coreaudio
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.transport import upload


def test_stereo_tap_pipe_to_existing_upload(tmp_path, monkeypatch):
    helper = tmp_path / "fake-tap.py"
    helper.write_text(
        """import struct, sys, time
assert sys.argv[1:] == ["stream", "41,42"]
out = sys.stdout.buffer
out.write(struct.pack("<4sI", b"SVCT", 44100))
out.flush()
frames = 882
for index in range(100):
    out.write(struct.pack("<QQII", time.monotonic_ns(), index * frames, frames, 0))
    # A 1.5 mixed L peak exceeds full scale; downmix and headroom should
    # produce 0.4. A middle idle interval is still a live stream.
    out.write(struct.pack("<ff", 1.5, .5) * frames if index < 25 or index >= 50 else bytes(frames * 8))
    out.flush()
    time.sleep(.02)
""",
        encoding="ascii",
    )
    children = []
    start_process = coreaudio.subprocess.Popen

    def popen(args, **kwargs):
        process = start_process([sys.executable, *args], **kwargs)
        children.append(process)
        return process

    monkeypatch.setattr(coreaudio.subprocess, "Popen", popen)
    token = tmp_path / "token"
    token.write_text("c" * 64 + "\n")
    source = coreaudio.CoreAudioSource([41, 42], helper=helper)

    async def run():
        received = []

        async def handler(connection):
            assert json.loads(await connection.recv())["type"] == "start"
            assert not children
            await connection.send(json.dumps({"type": "ready", "version": 1,
                "session_id": "00112233-4455-6677-8899-aabbccddeeff", "frame_samples": 960, "max_buffer_frames": 10}))
            for _ in range(80):
                frame = await connection.recv()
                assert len(frame) == 1944
                received.append(float(np.median(np.frombuffer(frame[-1920:], dtype="<i2"))))
            await connection.send('{"type":"stopped","reason":"admin_stop"}')

        async with asyncio.timeout(5), serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            with pytest.raises(SenderError) as error:
                await upload(source, url=f"ws://127.0.0.1:{port}/v1/audio", token_file=token)
            assert error.value.code == "server_stopped"
        assert sum(abs(value - 13107) < 5 for value in received[:25]) >= 10
        assert sum(value == 0 for value in received[30:48]) >= 12
        assert sum(abs(value - 13107) < 5 for value in received[58:]) >= 10
        assert source.stats()["clipped_samples"] == 0
        source.verify_check_signal()

    try:
        asyncio.run(run())
    finally:
        source.close()
    assert len(children) == 1 and children[0].poll() is not None
