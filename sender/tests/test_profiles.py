"""Profile isolation and protocol tests. Fixture CA is public; no private key is stored."""
import asyncio
import base64
import json
import os
from pathlib import Path
import ssl
import time
from types import SimpleNamespace
from uuid import uuid4

import pytest
from svc_broadcast_sender import cli, profiles, transport
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.sources import ToneSource

CA = (Path(__file__).parent / "fixtures/test-ca.pem").read_text()


def info():
    return dict(version=1, server_id=str(uuid4()), url="wss://127.0.0.1:24458/v1/device/audio",
                ca_pem=CA, code="a" * 64, expires_at=int(time.time() * 1000) + 300000)


def blob(data):
    return "svcb1:" + base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip("=")


def saved():
    data = info()
    del data["code"]
    return {**data, "token": "b" * 64, "device_id": str(uuid4()), "player_id": str(uuid4())}


def test_specific_ca_context_does_not_load_other_roots_or_enable_key_logging(monkeypatch, tmp_path):
    monkeypatch.setenv("SSLKEYLOGFILE", str(tmp_path / "keylog"))
    context = profiles.profile_context(CA)
    assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname
    assert context.minimum_version >= ssl.TLSVersion.TLSv1_2
    assert len(context.get_ca_certs()) == 1
    assert context.keylog_filename is None
    assert not (tmp_path / "keylog").exists()


@pytest.mark.parametrize("change", [
    {"version": True}, {"version": 2}, {"code": "secret"}, {"expires_at": 0},
    {"expires_at": True}, {"expires_at": 2**63}, {"server_id": "invalid"},
    {"url": "ws://127.0.0.1:24458/v1/device/audio"},
    {"url": "wss://secret@example.com/v1/device/audio"},
    {"url": "wss://127.0.0.1:24458/v1/audio"}, {"ca_pem": "not a cert"},
])
def test_untrusted_or_malformed_pairing_information_has_safe_errors(change):
    with pytest.raises(SenderError) as error:
        profiles.parse_pairing(blob({**info(), **change}))
    assert "secret" not in str(error.value)
    assert "pairing_info" == error.value.code


def test_oversized_duplicate_and_extra_pair_fields_are_rejected():
    with pytest.raises(SenderError): profiles.parse_pairing("svcb1:" + "a" * 16384)
    with pytest.raises(SenderError): profiles.parse_pairing(blob({**info(), "extra": "field"}))
    raw = json.dumps(info())[:-1] + ',"version":1}'
    with pytest.raises(SenderError):
        profiles.parse_pairing("svcb1:" + base64.urlsafe_b64encode(raw.encode()).decode().rstrip("="))


def test_game_pairing_reuses_only_the_same_player_and_trusted_identity(tmp_path):
    data = saved()
    identity = {key: data[key] for key in ("version", "server_id", "url", "ca_pem")}
    profiles.save_profile("manual", data, tmp_path)
    assert profiles.find_paired_profile("mc-test", data["player_id"], identity, tmp_path) == "manual"
    assert profiles.find_paired_profile("mc-test", str(uuid4()), identity, tmp_path) is None
    changed = {**identity, "server_id": str(uuid4())}
    with pytest.raises(SenderError) as error:
        profiles.find_paired_profile("mc-test", data["player_id"], changed, tmp_path)
    assert error.value.code == "profile_identity_changed"
    profiles.save_profile("mc-test", data, tmp_path)
    with pytest.raises(SenderError) as error:
        profiles.find_paired_profile("mc-test", str(uuid4()), identity, tmp_path)
    assert error.value.code == "profile_identity_changed"


def test_game_pairing_refuses_an_offer_with_changed_identity_before_network(tmp_path, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("changed identity reached network")
    monkeypatch.setattr(profiles.http.client, "HTTPSConnection", forbidden)
    offer = info()
    identity = {key: offer[key] for key in ("version", "server_id", "url", "ca_pem")}
    identity["server_id"] = str(uuid4())
    with pytest.raises(SenderError) as error:
        profiles.pair("mc-test", blob(offer), "Minecraft sender", tmp_path, expected_identity=identity)
    assert error.value.code == "profile_identity_changed"


def test_private_profile_roundtrip_no_silent_overwrite_or_traversal(tmp_path):
    directory = tmp_path / "profiles"
    data = saved()
    profiles.save_profile("home", data, directory)
    assert profiles.load_profile("home", directory) == data
    if os.name != "nt":
        assert directory.stat().st_mode & 0o777 == 0o700
        assert (directory / "home.json").stat().st_mode & 0o777 == 0o600
    assert profiles.list_profiles(directory) == ["home"]
    with pytest.raises(SenderError): profiles.save_profile("home", saved(), directory)
    assert profiles.load_profile("home", directory) == data
    for bad in ("../escape", "/absolute", "a/b", "", "a" * 33):
        with pytest.raises(SenderError): profiles.profile_path(bad, directory)
    profiles.remove_profile("home", directory)
    assert profiles.list_profiles(directory) == []


def test_corrupt_and_symlink_profile_cannot_be_overwritten_or_followed(tmp_path):
    root = tmp_path / "profiles"
    root.mkdir()
    other = tmp_path / "other"
    other.write_text("secret")
    (root / "link.json").symlink_to(other)
    with pytest.raises(SenderError): profiles.load_profile("link", root)
    with pytest.raises(SenderError): profiles.save_profile("link", saved(), root)
    assert other.read_text() == "secret"
    (root / "bad.json").write_text("bad secret")
    with pytest.raises(SenderError) as error: profiles.load_profile("bad", root)
    assert "secret" not in str(error.value)


def test_pairing_checks_storage_before_consuming_code(monkeypatch, tmp_path):
    def unsafe(*args, **kwargs): raise OSError("secret filesystem detail")
    def forbidden(*args, **kwargs): pytest.fail("network used before private storage is ready")
    monkeypatch.setattr(profiles, "_private", unsafe)
    monkeypatch.setattr(profiles.http.client, "HTTPSConnection", forbidden)
    with pytest.raises(SenderError) as error: profiles.pair("home", blob(info()), "Mac", tmp_path)
    assert error.value.code == "profile_permissions" and "secret" not in str(error.value)


@pytest.mark.parametrize("failure", [None, "redirect", "wrong_server", "tls"])
def test_pairing_uses_trusted_https_exact_server_and_never_follows_redirects(monkeypatch, tmp_path, failure):
    initial = info()
    issued = saved()
    response_data = {k: v for k, v in issued.items() if k not in ("url", "ca_pem")}
    response_data["server_id"] = initial["server_id"] if failure != "wrong_server" else str(uuid4())
    closed = []

    class Connection:
        def __init__(self, host, port, *, context, timeout):
            assert host == "127.0.0.1" and port == 24458
            assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname
            assert timeout == 5
        def request(self, method, path, *, body, headers):
            assert method == "POST" and path == "/v1/pair"
            assert json.loads(body) == dict(code=initial["code"], server_id=initial["server_id"], label="Mac")
            if failure == "tls": raise ssl.SSLCertVerificationError("secret certificate detail")
        def getresponse(self):
            return SimpleNamespace(status=302 if failure == "redirect" else 200,
                                   read=lambda size: json.dumps(response_data).encode())
        def close(self): closed.append(True)

    monkeypatch.setattr(profiles.http.client, "HTTPSConnection", Connection)
    if failure:
        with pytest.raises(SenderError) as error: profiles.pair("home", blob(initial), "Mac", tmp_path)
        assert "secret" not in str(error.value)
        assert profiles.list_profiles(tmp_path) == []
    else:
        profiles.pair("home", blob(initial), "Mac", tmp_path)
        data = profiles.load_profile("home", tmp_path)
        assert data["server_id"] == initial["server_id"] and data["ca_pem"] == CA
    assert closed == [True]


def test_profile_upload_rejects_credential_or_endpoint_overrides_before_network(monkeypatch):
    def forbidden(*args, **kwargs): pytest.fail("override reached network")
    monkeypatch.setattr(transport, "connect", forbidden)
    data = saved()
    for kwargs in [dict(token_file="secret"), dict(ca_file="secret"), dict(url="wss://other.example/v1/device/audio")]:
        options = dict(url=data["url"], profile=data)
        options.update(kwargs)
        with pytest.raises(SenderError) as error: asyncio.run(transport.upload(ToneSource(0.02), **options))
        assert error.value.code == "profile_override"


def test_pair_cli_reads_hidden_input_and_does_not_open_audio(monkeypatch, tmp_path, capsys):
    captured = []
    monkeypatch.setattr(cli.sys, "stdin", SimpleNamespace(isatty=lambda: True))
    monkeypatch.setattr(cli, "_read_pairing_info", lambda: "private pairing blob")
    monkeypatch.setattr(profiles, "pair", lambda *args: captured.append(args))
    assert cli.main(["--profiles-dir", str(tmp_path), "pair", "--name", "home", "--label", "Mac"]) == 0
    assert captured == [("home", "private pairing blob", "Mac", str(tmp_path))]
    assert "private pairing blob" not in capsys.readouterr().out


def test_pair_cli_refuses_piped_input_instead_of_echoing_secret(monkeypatch, capsys):
    monkeypatch.setattr(cli.sys, "stdin", SimpleNamespace(isatty=lambda: False))
    monkeypatch.setattr(cli, "_read_pairing_info", lambda: pytest.fail("hidden input attempted without terminal"))
    assert cli.main(["pair", "--name", "home", "--label", "Mac"]) == 2
    assert "pairing_terminal" in capsys.readouterr().err


def test_pair_cli_fails_before_getpass_can_fall_back_to_visible_input(monkeypatch, capsys):
    import warnings
    monkeypatch.setattr(cli.sys, "platform", "win32")
    monkeypatch.setattr(cli.sys, "stdin", SimpleNamespace(isatty=lambda: True))
    def unavailable(prompt):
        warnings.warn("terminal echo control failed", cli.getpass.GetPassWarning)
        pytest.fail("visible-input fallback was reached")
    monkeypatch.setattr(cli.getpass, "getpass", unavailable)
    monkeypatch.setattr(profiles, "pair", lambda *args: pytest.fail("pairing used an unsafe terminal"))
    assert cli.main(["pair", "--name", "home", "--label", "Mac"]) == 2
    assert "pairing_terminal" in capsys.readouterr().err


@pytest.mark.parametrize("cancel", [False, True])
def test_pair_terminal_hides_long_pastes_and_restores_mode(cancel):
    import os
    import select
    import signal
    import subprocess
    import sys

    pty = pytest.importorskip("pty")
    termios = pytest.importorskip("termios")
    master, slave = pty.openpty()
    try:
        original = termios.tcgetattr(slave)
        length = 2048  # Larger than macOS's canonical line limit.
        code = ("from svc_broadcast_sender.cli import _read_pairing_info\n"
                "try: value = _read_pairing_info()\n"
                "except KeyboardInterrupt: print('CANCELLED')\n"
                f"else: print('MATCH' if value == 'p' * {length} else 'MISMATCH')\n")
        with subprocess.Popen([sys.executable, "-c", code], stdin=slave, stdout=slave, stderr=slave,
                              start_new_session=True) as process:
            try:
                output = bytearray()
                while b"(hidden):" not in output:
                    assert select.select([master], [], [], 5)[0], "No hidden prompt"
                    output.extend(os.read(master, 4096))
                if cancel:
                    process.send_signal(signal.SIGINT)
                else:
                    os.write(master, b"p" * length + b"\n")
                assert process.wait(timeout=5) == 0
                while select.select([master], [], [], 0)[0]:
                    output.extend(os.read(master, 4096))
                assert (b"CANCELLED" if cancel else b"\r\nMATCH\r\n") in output
                assert b"p" * 32 not in output
                assert termios.tcgetattr(slave) == original
            finally:
                if process.poll() is None:
                    process.kill()
    finally:
        os.close(master)
        os.close(slave)


def test_explicit_remove_can_recover_corrupt_profile_without_reading_secrets(tmp_path):
    (tmp_path / "bad.json").write_text("not json")
    profiles.remove_profile("bad", tmp_path)
    assert not (tmp_path / "bad.json").exists()
