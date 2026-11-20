import json
import ssl
import struct
import wave
from uuid import UUID

import pytest

from svc_broadcast_sender.cli import main
from svc_broadcast_sender.config import read_token, tls_context, validate_url
from svc_broadcast_sender.errors import InputError, SenderError
from svc_broadcast_sender.protocol import PCM_BYTES, Ready, pack_frame, parse_control
from svc_broadcast_sender.sources import ToneSource, WavSource



@pytest.mark.parametrize("url", [
    "ws://example.com/v1/audio",
    "ws://192.168.1.2/v1/audio",
    "ws://127.0.0.1.example.com/v1/audio",
    "ws://2130706433/v1/audio",
    "ws://127.1/v1/audio",
    "ws://localhost./v1/audio",
    "wss://secret@example.com/v1/audio",
    "wss://example.com/v1/audio?token=secret",
    "wss://example.com/v1/audio?",
    "wss://example.com/v1/audio#",
    "wss://example.com/v1/audio/",
    "ws://localhost:0/v1/audio",
    "ws://localhost:65536/v1/audio",
    "ws://localhost:/v1/audio",
    "ws://[::1/v1/audio",
    " ws://localhost/v1/audio",
    "ws://local\nhost/v1/audio",
    "https://localhost/v1/audio",
])
def test_unsafe_or_malformed_endpoints_fail_without_echo(url):
    with pytest.raises(InputError) as error:
        validate_url(url)
    assert url not in str(error.value)
    assert "secret" not in str(error.value)


def test_tls_verification_remains_enabled(tmp_path):
    context = tls_context("wss://example.com/v1/audio", None)
    assert context.check_hostname
    assert context.verify_mode == ssl.CERT_REQUIRED
    with pytest.raises(InputError):
        tls_context("ws://localhost/v1/audio", tmp_path / "ca.pem")
    ca = tmp_path / "ca.pem"
    ca.write_text("not a certificate")
    with pytest.raises(InputError) as error:
        tls_context("wss://example.com/v1/audio", ca)
    assert error.value.code == "ca_file"


@pytest.mark.parametrize("suffix", [b"", b"\n", b"\r\n"])
def test_token_file_accepts_only_single_token(tmp_path, suffix):
    path = tmp_path / "token"
    path.write_bytes(b"a1" * 32 + suffix)
    assert read_token(path) == "a1" * 32


@pytest.mark.parametrize("raw", [b"", b"a" * 63, b"a" * 65, b"A" * 64, b"g" * 64, b"a" * 64 + b"\nextra", b" " + b"a" * 64, b"a" * 64 + b"\n\n", b"\xff" * 64])
def test_invalid_token_files_have_safe_errors(tmp_path, raw):
    path = tmp_path / "token"
    path.write_bytes(raw)
    with pytest.raises(InputError) as error:
        read_token(path)
    assert error.value.code == "token_format"
    assert "a" * 63 not in str(error.value)


def _wav(path, pcm, *, rate=48000, channels=1, width=2):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(width)
        output.setframerate(rate)
        output.writeframes(pcm)


def test_wav_seeks_skipped_frames_and_pads_only_final_frame(tmp_path):
    path = tmp_path / "audio.wav"
    frames = [struct.pack("<960h", *([sample] * 960)) for sample in (100, 200, 300)]
    ending = struct.pack("<3h", -32768, 0, 32767)
    _wav(path, b"".join(frames) + ending)
    with WavSource(path) as source:
        assert source.frame_count == 4
        assert source.read_frame(0) == frames[0]
        assert source.read_frame(2) == frames[2]
        assert source.read_frame(3) == ending + bytes(PCM_BYTES - len(ending))
        with pytest.raises(InputError):
            source.read_frame(4)


@pytest.mark.parametrize("parameters", [
    {"rate": 44100}, {"channels": 2}, {"width": 1}, {"width": 3},
])
def test_wav_refuses_implicit_conversion(tmp_path, parameters):
    path = tmp_path / "wrong.wav"
    _wav(path, bytes(1920), **parameters)
    with pytest.raises(InputError) as error:
        WavSource(path)
    assert error.value.code == "wav_format"


def test_wav_rejects_empty_compressed_and_truncated_input(tmp_path):
    path = tmp_path / "audio.wav"
    _wav(path, b"")
    with pytest.raises(InputError) as error:
        WavSource(path)
    assert error.value.code == "wav_empty"
    _wav(path, bytes(1920))
    data = bytearray(path.read_bytes())
    data[20:22] = struct.pack("<H", 3)  # IEEE float format is not integer PCM.
    path.write_bytes(data)
    with pytest.raises(InputError):
        WavSource(path)
    _wav(path, bytes(1920))
    with path.open("r+b") as file:
        file.truncate(44 + 10)
    with WavSource(path) as source:
        with pytest.raises(InputError) as error:
            source.read_frame(0)
    assert error.value.code == "wav_truncated"


@pytest.mark.parametrize("seconds", [0, -1, 31, float("inf"), float("nan")])
def test_tone_rejects_invalid_duration(seconds):
    with pytest.raises(InputError):
        ToneSource(seconds)


def test_tone_is_finite_quiet_and_fades_to_silence():
    source = ToneSource(0.05)
    assert source.frame_count == 3
    pcm = b"".join(source.read_frame(i) for i in range(source.frame_count))
    samples = struct.unpack("<2880h", pcm)
    assert samples[0] == samples[2399] == 0
    assert all(value == 0 for value in samples[2400:])
    assert 700 < max(abs(value) for value in samples) <= 824


def test_frame_layout_and_sequence_bounds():
    session = UUID("00112233-4455-6677-8899-aabbccddeeff")
    pcm = struct.pack("<960h", *range(960))
    packet = pack_frame(session, 0x0102030405060708, pcm)
    assert len(packet) == 1944
    assert packet[:16] == bytes.fromhex("00112233445566778899aabbccddeeff")
    assert packet[16:24] == bytes.fromhex("0102030405060708")
    assert packet[24:] == pcm
    for sequence in (-1, 1 << 63, True):
        with pytest.raises(SenderError):
            pack_frame(session, sequence, pcm)
    with pytest.raises(SenderError):
        pack_frame(session, 0, pcm[:-2])


def _ready(**overrides):
    message = {"type": "ready", "version": 1, "session_id": "00112233-4455-6677-8899-aabbccddeeff", "frame_samples": 960, "max_buffer_frames": 10}
    message.update(overrides)
    return json.dumps(message)


def test_ready_validates_all_protocol_fields():
    assert isinstance(parse_control(_ready()), Ready)
    for override in ({"version": True}, {"version": 2}, {"frame_samples": 480}, {"max_buffer_frames": 11}, {"session_id": "bad"}, {"session_id": "00112233445566778899aabbccddeeff"}):
        with pytest.raises(SenderError):
            parse_control(_ready(**override))
    for malformed in (b"binary", "[]", "{", '{"type":"stopped","type":"ready"}', " " * 1025):
        with pytest.raises(SenderError):
            parse_control(malformed)


@pytest.mark.parametrize("code, expected", [
    ("rate_exceeded", "arrival rate exceeded"),
    ("future_sequence", "ahead of the server clock"),
    ("rate_exceeded secret", "Server rejected the stream."),
])
def test_server_error_diagnostics_use_only_fixed_allowlisted_messages(code, expected):
    with pytest.raises(SenderError) as error:
        parse_control(json.dumps({"type": "error", "code": code}))
    assert expected in str(error.value)
    assert "secret" not in str(error.value)


def test_remote_error_and_cli_diagnostics_never_echo_secrets(tmp_path, capsys):
    secret = "c" * 64
    with pytest.raises(SenderError) as error:
        parse_control(json.dumps({"type": "error", "code": secret}))
    assert secret not in str(error.value)
    token = tmp_path / "token"
    token.write_text(secret + "invalid")
    assert main(["--token-file", str(token), "tone", "--seconds", "0.02"]) == 2
    output = capsys.readouterr()
    assert secret not in output.out + output.err
    assert "Traceback" not in output.err
    assert "token_format" in output.err
    assert main(["--url", f"wss://{secret}@example.com/v1/audio", "--token-file", str(token), "tone"]) == 2
    output = capsys.readouterr()
    assert secret not in output.out + output.err
    assert main([]) == 2
