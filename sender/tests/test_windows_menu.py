"""Menu safety checks on the host; these do not claim Windows native execution."""
import importlib.util
from pathlib import Path
import sys

import pytest
from svc_broadcast_sender import cli, capture


@pytest.fixture
def menu(monkeypatch, tmp_path):
    path = Path(__file__).parents[1] / "windows/windows_test.py"
    original = sys.path[:]
    spec = importlib.util.spec_from_file_location("windows_test_menu", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setattr(module, "ROOT", tmp_path)
    monkeypatch.setattr(capture, "list_devices", lambda: [capture.CaptureDevice(
        4, "CABLE Output (VB-Audio Virtual Cable)", "Windows WASAPI", 2, 0, 48000)])
    yield module
    sys.path[:] = original


def answers(monkeypatch, values):
    iterator = iter(values)
    monkeypatch.setattr("builtins.input", lambda prompt="": next(iterator))


def test_exit_and_cancel_never_start_capture_or_network(menu, monkeypatch):
    monkeypatch.setattr(cli, "main", lambda *a: pytest.fail("cancelled action reached CLI"))
    answers(monkeypatch, ["6", "1", "", "5", "", "0"])
    assert menu.menu() == 0
    assert not (menu.ROOT / "logs").exists()


def test_local_check_is_explicit_device_and_has_no_profile_or_upload(menu, monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "main", lambda args: calls.append(args) or 0)
    answers(monkeypatch, ["4", "1", "START", "0"])
    assert menu.menu() == 0
    assert len(calls) == 1
    assert calls[0][0] == "check-input"
    assert calls[0][calls[0].index("--device") + 1] == "CABLE Output (VB-Audio Virtual Cable)"
    assert calls[0][calls[0].index("--host-api") + 1] == "Windows WASAPI"
    assert calls[0][-2:] == ["--seconds", "10"]
    assert "--profile" not in calls[0]


def test_pairing_never_uses_diagnostic_logging_or_device_selection(menu, monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "main", lambda args: calls.append(args) or 0)
    monkeypatch.setattr(menu, "logged", lambda *a: pytest.fail("pairing was logged"))
    monkeypatch.setattr(menu, "choose_device", lambda: pytest.fail("pairing opened capture selection"))
    answers(monkeypatch, ["3", "0"])
    assert menu.menu() == 0
    assert calls == [["pair", "--name", "hangzhou-win", "--label", "Windows11-x64"]]


def test_tone_requires_confirmation_and_is_bounded_to_one_second(menu, monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "main", lambda args: calls.append(args) or 0)
    answers(monkeypatch, ["5", "START", "0"])
    assert menu.menu() == 0
    assert calls == [["tone", "--profile", "hangzhou-win", "--seconds", "1"]]


def test_capture_is_explicit_profile_and_ctrl_c_result_is_recorded(menu, monkeypatch):
    calls = []
    monkeypatch.setattr(cli, "main", lambda args: calls.append(args) or 130)
    answers(monkeypatch, ["6", "1", "START", "0"])
    assert menu.menu() == 0
    assert len(calls) == 1 and calls[0][:3] == ["capture", "--profile", "hangzhou-win"]
    assert "exit_code=130" in next((menu.ROOT / "logs").glob("*.log")).read_text()


def test_native_check_and_broadcast_require_confirmation_and_use_same_selection(menu, monkeypatch):
    from svc_broadcast_sender import wasapi

    calls = []
    monkeypatch.setattr(menu, "BUNDLE_VERSION", "p4c-win-live-test3")
    monkeypatch.setattr(wasapi, "list_apps", lambda: [
        wasapi.AppTarget(123, 7654, "网易云音乐"), wasapi.AppTarget(456, 7655, "Music")])
    monkeypatch.setattr(menu, "choose_profile", lambda: "p4b-test")
    monkeypatch.setattr(cli, "main", lambda args: calls.append(args) or 0)
    answers(monkeypatch, ["9", "1,2", "", "8", "1,2", "START", "9", "1,2", "START", "0"])
    assert menu.menu() == 0
    assert calls == [
        ["check-apps", "--app", "123:7654", "--app", "456:7655", "--seconds", "30"],
        ["broadcast-apps", "--profile", "p4b-test", "--app", "123:7654", "--app", "456:7655"],
    ]
