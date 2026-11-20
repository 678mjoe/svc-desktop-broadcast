import asyncio
import json
import os
import sys

import pytest
from websockets.asyncio.server import serve

from svc_broadcast_sender import cli, coreaudio, mc_bridge, transport, wasapi
from svc_broadcast_sender.errors import SenderError


def test_app_list_filters_game_and_validates_mac_identity(monkeypatch, capsys):
    monkeypatch.setattr(sys, "platform", "darwin")
    monkeypatch.setattr(coreaudio, "list_apps", lambda *, identities: (
        "14:100:0\t\tMinecraft\n42:111:333\tcom.player\t网易云音乐\n"
    ))
    assert cli.main(["mc-bridge", "apps", "--game-pid", "14"]) == 0
    assert json.loads(capsys.readouterr().out) == {
        "event": "apps", "items": [{"id": "42:111:333", "label": "网易云音乐 · com.player"}],
    }
    for selections in (["14:100:0"], ["42:111:333", "42:555:0"], ["42:missing:0"]):
        with pytest.raises(SenderError, match="apps"):
            mc_bridge._source(selections, 14)


def test_profile_names_only_and_missing_profile_has_safe_error(tmp_path, capsys):
    (tmp_path / "private.json").write_text("a private test value")
    args = ["--profiles-dir", str(tmp_path), "mc-bridge"]
    assert cli.main([*args, "profiles", "--game-pid", "14"]) == 0
    report = capsys.readouterr()
    assert json.loads(report.out) == {"event": "profiles", "items": ["private"]}
    assert not report.err
    assert cli.main([*args, "start", "--profile=missing", "--app=42:111:333", "--game-pid", "14"]) == 2
    assert json.loads(capsys.readouterr().out) == {"event": "error", "code": "profile_file"}


def test_windows_receives_game_pid_for_process_tree_exclusion(monkeypatch):
    monkeypatch.setattr(sys, "platform", "win32")

    def list_apps(*, excluded_pid):
        assert excluded_pid == 14
        return [wasapi.AppTarget(14, 100, "Minecraft"), wasapi.AppTarget(42, 110, "Player", True)]

    def source_from_selection(pids, selectors, *, excluded_pid):
        assert (pids, selectors, excluded_pid) == (None, ["42:110"], 14)

    monkeypatch.setattr(wasapi, "list_apps", list_apps)
    monkeypatch.setattr(wasapi, "source_from_selection", source_from_selection)
    assert mc_bridge._apps(14) == [{"id": "42:110", "label": "Player · 活动音频"}]
    mc_bridge._source(["42:110"], 14)


@pytest.mark.parametrize("server_accepts", [True, False])
def test_java_control_pipe_eof_and_server_ready_gate(tmp_path, monkeypatch, server_accepts):
    calls = []
    events = []
    token = tmp_path / "token"
    token.write_text("b" * 64 + "\n")
    monkeypatch.setattr(mc_bridge, "_emit", lambda event: events.append(event))

    class FakeSource:
        ready = False

        def read_frame(self, index):
            calls.append("read")
            self.ready = True
            return bytes(1920)

        def close(self):
            calls.append("close")

    async def upload(source, *, url, profile):
        return await transport.upload(source, url=url, token_file=token)

    monkeypatch.setattr(mc_bridge, "upload", upload)

    async def run(reader, writer):
        async def handle(connection):
            assert json.loads(await connection.recv())["type"] == "start"
            assert not calls
            if not server_accepts:
                await connection.send('{"type":"stopped","reason":"admin_stop"}')
                return
            await connection.send('{"type":"ready","version":1,"session_id":"00112233-4455-6677-8899-aabbccddeeff",'
                                  '"frame_samples":960,"max_buffer_frames":10}')
            assert len(await connection.recv()) == 1944
            writer.close()
            assert json.loads(await connection.recv())["type"] == "stop"
            calls.append("server_stop")

        async with asyncio.timeout(5), serve(handle, "127.0.0.1", 0) as server:
            url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/v1/audio"
            if not server_accepts:
                with pytest.raises(SenderError) as error:
                    await mc_bridge._start({"url": url}, FakeSource(), reader.fileno())
                assert error.value.code == "server_stopped"
            else:
                await mc_bridge._start({"url": url}, FakeSource(), reader.fileno())

    read_fd, write_fd = os.pipe()
    with os.fdopen(read_fd, "rb") as reader, os.fdopen(write_fd, "wb") as writer:
        asyncio.run(run(reader, writer))
    if server_accepts:
        assert events == ["capture_starting", "capturing"]
        assert calls[0] == "read" and "server_stop" in calls and calls[-1] == "close"
    else:
        assert not events
        assert calls == ["close"]
