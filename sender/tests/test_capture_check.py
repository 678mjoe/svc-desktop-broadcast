"""Local checks must work without authentication, sockets or audio outputs."""

from types import SimpleNamespace

import pytest

from svc_broadcast_sender import capture, cli
from svc_broadcast_sender.errors import SenderError
from svc_broadcast_sender.protocol import PCM_BYTES


@pytest.mark.parametrize("outcome,exit_code", [("silence", 0), ("timeout", 4), ("interrupt", 130)])
def test_local_input_check_has_no_upload_and_always_releases_device(monkeypatch, capsys, outcome, exit_code):
    now = [100.0]
    sources = []
    device = object()

    class Source:
        def __init__(self, selected, **options):
            assert selected is device
            assert options == dict(sample_rate=48000, channels=2, gain_db=0)
            self.closed = False
            self.frames = 0
            sources.append(self)

        def read_frame(self, sequence):
            assert sequence == self.frames
            if sequence == 1:
                if outcome == "timeout":
                    raise SenderError("capture_timeout", "Audio capture did not provide a fresh frame in time.")
                if outcome == "interrupt":
                    raise KeyboardInterrupt
            now[0] += 0.02
            self.frames += 1
            return bytes(PCM_BYTES)

        def stats(self):
            return dict(peak_dbfs=float("-inf"), rms_dbfs=float("-inf"),
                        clipped_samples=0, dropped_frames=0, queued_frames=0)

        def diagnostics(self):
            assert not self.closed  # Failure evidence is collected before close.
            return {"frames_read": self.frames, "callbacks": self.frames}

        def close(self):
            self.closed = True

    def resolve(name, hostapi):
        assert (name, hostapi) == ("OBS Monitor", "Core Audio")
        return device

    def forbidden(*args, **kwargs):
        pytest.fail("local input check attempted network access or authentication")

    monkeypatch.setattr(capture, "resolve_device", resolve)
    monkeypatch.setattr(capture, "LiveSource", Source)
    monkeypatch.setattr(cli, "upload", forbidden)
    monkeypatch.setattr(cli, "time", SimpleNamespace(monotonic=lambda: now[0]))
    # Neither supplied global upload settings nor a missing token are consulted.
    assert cli.main(["--url", "wss://secret@example.com/v1/audio", "--token-file", "/missing/token",
                     "check-input", "--device", "OBS Monitor", "--host-api", "Core Audio",
                     "--seconds", "0.1"]) == exit_code
    assert len(sources) == 1 and sources[0].closed
    output = capsys.readouterr()
    assert "Capture diagnostics:" in output.out
    assert "secret" not in output.out + output.err
    if outcome == "silence":
        assert "Silence is valid input" in output.out
        assert "peak=-inf" in output.out
    elif outcome == "timeout":
        assert "capture_timeout" in output.err


@pytest.mark.parametrize("seconds", ["0", "-1", "61", "nan", "inf"])
def test_invalid_check_duration_never_opens_device(monkeypatch, seconds, capsys):
    def forbidden(*args):
        pytest.fail("invalid duration opened a device")

    monkeypatch.setattr(capture, "resolve_device", forbidden)
    assert cli.main(["check-input", "--device", "OBS Monitor", "--seconds", seconds]) == 2
    assert "check_duration" in capsys.readouterr().err


def test_check_requires_explicit_device(monkeypatch, capsys):
    def forbidden(*args):
        pytest.fail("a default device was selected")

    monkeypatch.setattr(capture, "resolve_device", forbidden)
    assert cli.main(["check-input"]) == 2
    assert "arguments" in capsys.readouterr().err
