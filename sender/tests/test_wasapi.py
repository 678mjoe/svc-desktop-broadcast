"""Verify the binary pipe and upload without requiring Windows audio devices."""

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import sys

import numpy as np
import pytest
from websockets.asyncio.server import serve

from svc_broadcast_sender import wasapi
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.transport import upload


def test_list_apps_keeps_process_identity_and_sanitizes_display_name(monkeypatch, tmp_path):
    helper = tmp_path / "meter.exe"
    monkeypatch.setattr(wasapi, "bundled_helper", lambda: helper)
    monkeypatch.setattr(wasapi.subprocess, "CREATE_NO_WINDOW", 0, raising=False)

    def enumerate_apps(command, **options):
        assert command == [helper, "apps"]
        assert options["encoding"] == "utf-8"
        return SimpleNamespace(stdout="42\t987654\tW\tA\u202e Player\n43\t123456\tA\t网易云音乐\n")

    monkeypatch.setattr(wasapi.subprocess, "run", enumerate_apps)
    apps = wasapi.list_apps()
    assert [(app.label, app.selector) for app in apps] == [
        ("网易云音乐", "43:123456"), ("A Player", "42:987654")]
    assert [app.active_session for app in apps] == [True, False]
    assert wasapi.source_from_selection(None, [apps[0].selector])._selection == "43:123456"


def test_mixer_aligns_tracks_and_keeps_gain_fixed_when_one_pauses():
    mix = wasapi._MixBuffer(2)
    mix.tick(100_000)
    start = mix.cursor
    block = wasapi._BLOCK
    full = np.full((block, 2), 32767, np.int16)
    mix.add(0, start, full)
    mix.add(1, start + block // 2, full)
    mix.tick(100_000 + block)
    audio = mix.pop()
    np.testing.assert_allclose(audio[:block // 2], 0.4, atol=1e-4)
    np.testing.assert_allclose(audio[block // 2:], 0.8, atol=1e-4)
    mix.tick(100_000 + 2 * block)
    audio = mix.pop()
    np.testing.assert_allclose(audio[:block // 2], 0.4, atol=1e-4)
    assert not audio[block // 2:].any()
    mix.tick(100_000 + 100 * block)
    mix.add(0, start, full)
    assert not mix.pop().any()
    assert mix.dropped_frames == 94


def test_mixer_reports_frames_outside_its_timestamp_window():
    mix = wasapi._MixBuffer(1)
    mix.tick(100_000)
    signal = np.full((16, 2), -32768, dtype=np.int16)
    start = mix.cursor
    assert mix.add(0, start - 32, signal) == (0, 16, 0)
    assert mix.add(0, start - 8, signal) == (8, 8, 0)
    assert mix.add(0, start + wasapi._CAPACITY - 4, signal) == (4, 0, 12)
    assert mix.add(0, start + wasapi._CAPACITY, signal) == (0, 0, 16)


def test_synthetic_multitrack_pipe_through_existing_websocket(tmp_path, monkeypatch):
    # Run an actual child pipe with known stereo packets and heartbeat-only
    # intervals. The fixture uses the same record shape as the Windows helper.
    helper = tmp_path / "mock_helper.py"
    helper.write_text(
        """import struct, sys, time
assert sys.argv[1:] == ["stream", "42,43"]
out = sys.stdout.buffer
header = struct.Struct("<QII")
origin = 1_000_000_000
step = 200_000
frames = 882
for tick in range(140):
    timestamp = origin + tick * step
    # Emulate coalesced Windows timer wakes: two ready blocks per heartbeat.
    if tick % 2 == 0:
        out.write(header.pack(timestamp, 0, 0))
    amplitudes = (10000, 20000) if tick < 28 or tick >= 70 else ()
    for index, amplitude in enumerate(amplitudes, 1):
        out.write(header.pack(timestamp, index, frames))
        out.write(struct.pack("<hh", amplitude, amplitude) * frames)
    out.flush()
    time.sleep(.02)
""",
        encoding="ascii",
    )
    popen = wasapi.subprocess.Popen
    children = []

    def start(argv, **kwargs):
        process = popen([sys.executable, *argv], **kwargs)
        children.append(process)
        return process

    monkeypatch.setattr(wasapi.subprocess, "Popen", start)
    token = tmp_path / "token"
    token.write_text("c" * 64 + "\n")
    source = wasapi.WasapiSource([42, 43], helper=Path(helper))

    async def exercise():
        received = []

        async def handler(connection):
            assert json.loads(await connection.recv())["type"] == "start"
            assert not children
            await connection.send(json.dumps({"type": "ready", "version": 1,
                "session_id": "00112233-4455-6677-8899-aabbccddeeff", "frame_samples": 960, "max_buffer_frames": 10}))
            while len(received) < 110:
                message = await connection.recv()
                assert len(message) == 1944
                received.append(np.frombuffer(message[-1920:], dtype="<i2"))
            await connection.send('{"type":"stopped","reason":"admin_stop"}')

        async with asyncio.timeout(6), serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            with pytest.raises(SenderError) as stopped:
                await upload(source, url=f"ws://127.0.0.1:{port}/v1/audio", token_file=token)
            assert stopped.value.code == "server_stopped"
        levels = [float(np.median(block)) for block in received]
        assert sum(abs(level - 12000) < 5 for level in levels[:40]) >= 10
        assert sum(level == 0 for level in levels[40:68]) >= 20
        assert sum(abs(level - 12000) < 5 for level in levels[85:]) >= 10
        assert source.stats()["clipped_samples"] == 0
        assert source.diagnostics()["max_clock_step_ms"] == 40
        assert source.diagnostics()["converter_resets"] == 0
        assert source.diagnostics()["raw_peak_s16"] == [10000, 20000]
        assert all(count > 0 for count in source.diagnostics()["placed_signal_packets"])
        source.verify_check_signal()

    try:
        asyncio.run(exercise())
    finally:
        source.close()
    assert len(children) == 1 and children[0].poll() is not None


def test_server_stop_before_ready_leaves_capture_closed(tmp_path):
    token = tmp_path / "token"
    token.write_text("c" * 64 + "\n")
    source = wasapi.WasapiSource([42], helper=tmp_path / "not-started")

    async def exercise():
        async def handler(connection):
            assert json.loads(await connection.recv())["type"] == "start"
            await connection.send('{"type":"stopped","reason":"admin_stop"}')

        async with asyncio.timeout(3), serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            with pytest.raises(SenderError) as stopped:
                await upload(source, url=f"ws://127.0.0.1:{port}/v1/audio", token_file=token)
            assert stopped.value.code == "server_stopped"
    try:
        asyncio.run(exercise())
    finally:
        source.close()
    assert source._process is None
