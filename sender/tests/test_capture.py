from concurrent.futures import Future
import threading
import time
from types import SimpleNamespace

import numpy as np
import pytest

from svc_broadcast_sender import capture
from svc_broadcast_sender.audio import PcmConverter
from svc_broadcast_sender.errors import InputError, SenderError
from svc_broadcast_sender.protocol import PCM_BYTES


class FakeBackend:
    """PortAudio test fixture: no sounddevice import or real device access."""

    def __init__(self):
        self.devices = [self.device("OBS Monitor")]
        self.hosts = [{"name": "Core Audio"}, {"name": "Other API"}]
        self.streams = []
        self.initial = []
        self.opened = threading.Event()
        self.open_gate = None
        self.start_gate = None
        self.open_error = None

    @staticmethod
    def device(name, hostapi=0, inputs=2, outputs=2):
        return {"name": name, "hostapi": hostapi, "max_input_channels": inputs,
                "max_output_channels": outputs, "default_samplerate": 48000.0}

    def query_devices(self):
        return self.devices

    def query_hostapis(self):
        return self.hosts

    def InputStream(self, **kwargs):
        if self.open_error:
            raise self.open_error
        stream = FakeStream(self, kwargs)
        self.streams.append(stream)
        self.opened.set()
        if self.open_gate:
            assert self.open_gate.wait(2)
        return stream


class FakeStream:
    def __init__(self, backend, options):
        self.backend = backend
        self.options = options
        self.active = False
        self.starting = threading.Event()
        self.started = threading.Event()
        self.closed = threading.Event()

    def start(self):
        self.starting.set()
        if self.backend.start_gate:
            assert self.backend.start_gate.wait(2)
        self.active = True
        for samples in self.backend.initial:
            self.emit(samples)
        self.started.set()

    def emit(self, samples, *, age=None, status=False):
        if age is None:
            age = len(samples) / self.options["samplerate"]
        timing = SimpleNamespace(currentTime=10.0, inputBufferAdcTime=10.0 - age)
        self.options["callback"](samples, len(samples), timing, status)

    def stop(self):
        self.active = False
        self.options["finished_callback"]()

    def close(self):
        self.closed.set()


def samples(value, count=960, channels=2):
    return np.full((count, channels), value / 32768, dtype=np.float32)


def frame(value):
    return np.full(960, value, dtype="<i2").tobytes()


def reading(source, sequence):
    result = Future()

    def run():
        try:
            result.set_result(source.read_frame(sequence))
        except BaseException as error:
            result.set_exception(error)

    threading.Thread(target=run, daemon=True).start()
    return result


@pytest.fixture
def backend(monkeypatch):
    backend = FakeBackend()
    monkeypatch.setattr(capture, "_backend", lambda: backend)
    # Age/queue tests advance this clock explicitly; OS scheduling must not
    # turn prequeued fixture samples into accidentally stale audio.
    monkeypatch.setattr(capture, "time", SimpleNamespace(monotonic=lambda: 100.0))
    return backend


@pytest.fixture
def source_factory(backend):
    sources = []

    def create(**options):
        source = capture.LiveSource(capture.resolve_device("OBS Monitor"), **options)
        sources.append(source)
        return source

    yield create
    for source in sources:
        source.close()
    for gate in (backend.open_gate, backend.start_gate):
        if gate:
            gate.set()


def assert_code(result, code):
    with pytest.raises(SenderError) as error:
        result.result(timeout=1)
    assert error.value.code == code
    return error.value


def test_listing_resolving_and_constructor_do_not_open_stream(backend, source_factory):
    capture.list_devices()
    source = source_factory()
    assert not backend.streams
    source.close()
    assert_code(reading(source, 0), "capture_closed")
    assert not backend.streams


@pytest.mark.parametrize("name", ["missing", "OBS", "obs monitor", "0"])
def test_resolution_requires_exact_input_name(backend, name):
    backend.devices.append(backend.device(name, inputs=0))
    with pytest.raises(InputError) as error:
        capture.resolve_device(name)
    assert error.value.code == "capture_missing"
    assert not backend.streams


def test_ambiguous_name_requires_host_api_and_duplicate_identity_fails(backend):
    backend.devices.append(backend.device("OBS Monitor", hostapi=1))
    with pytest.raises(InputError) as error:
        capture.resolve_device("OBS Monitor")
    assert error.value.code == "capture_ambiguous"
    assert capture.resolve_device("OBS Monitor", "Other API").index == 1
    backend.devices.append(backend.device("OBS Monitor", hostapi=1))
    with pytest.raises(InputError) as error:
        capture.resolve_device("OBS Monitor", "Other API")
    assert error.value.code == "capture_ambiguous"


def test_hotplug_re_resolves_identity_instead_of_reusing_stale_index(backend, source_factory):
    source = source_factory()
    backend.devices.insert(0, backend.device("Unrelated microphone"))
    backend.initial = [samples(123)]
    assert reading(source, 0).result(timeout=1) == frame(123)
    options = backend.streams[0].options
    assert options["device"] == 1


@pytest.mark.parametrize("change,code", [
    ("missing", "capture_missing"), ("ambiguous", "capture_ambiguous"),
    ("channels", "capture_channels"),
])
def test_hotplug_missing_ambiguous_or_changed_capabilities_refuses_open(backend, source_factory, change, code):
    source = source_factory()
    if change == "missing":
        backend.devices[0] = backend.device("Unrelated microphone")
    elif change == "ambiguous":
        backend.devices.append(backend.device("OBS Monitor"))
    else:
        backend.devices[0]["max_input_channels"] = 1
    assert_code(reading(source, 0), code)
    assert not backend.streams


@pytest.mark.parametrize("options", [
    {"sample_rate": 0}, {"sample_rate": True}, {"channels": 0}, {"channels": 3},
    {"channels": True}, {"gain_db": float("nan")},
])
def test_invalid_constructor_configuration_never_opens(backend, source_factory, options):
    with pytest.raises(InputError):
        source_factory(**options)
    assert not backend.streams


def test_continuous_pcm_partial_blocks_and_sequence_skips(backend, source_factory):
    backend.initial = [samples(10, 300), samples(10, 660), samples(20),
                       samples(30, 400), samples(30, 560), samples(40)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(10)
    assert reading(source, 2).result(timeout=1) == frame(30)
    assert reading(source, 3).result(timeout=1) == frame(40)
    assert source.stats()["dropped_frames"] == 1
    assert source.stats()["queued_frames"] == 0
    assert source.stats()["peak_dbfs"] < 0
    with pytest.raises(InputError):
        source.read_frame(3)


def test_callback_copies_input_before_backend_reuses_it(backend, source_factory):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    data = samples(234)
    backend.streams[0].emit(data)
    data.fill(0)
    assert reading(source, 1).result(timeout=1) == frame(234)


def test_queue_drops_do_not_double_discard_on_sequence_jump(backend, source_factory):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    stream = backend.streams[0]
    for value in range(2, 17):
        stream.emit(samples(value))
    assert source.stats()["queued_frames"] <= 10
    # Timeline frames 1..5 were discarded by the queue. Requesting frame 6
    # must not discard those five frames again and return frame 11 instead.
    assert reading(source, 6).result(timeout=1) == frame(7)
    assert reading(source, 7).result(timeout=1) == frame(8)
    assert source.stats()["dropped_frames"] == 5


def test_normal_long_stream_does_not_reset_for_already_consumed_input(backend, source_factory):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    for sequence in range(1, 30):
        backend.streams[0].emit(samples(sequence + 1))
        assert reading(source, sequence).result(timeout=1) == frame(sequence + 1)
    assert source.stats()["dropped_frames"] == 0


def test_stale_callback_timestamp_is_discarded_without_replay(backend, source_factory):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    stream = backend.streams[0]
    stream.emit(samples(2), age=0.4)
    stream.emit(samples(3))
    assert reading(source, 2).result(timeout=1) == frame(3)
    assert source.stats()["dropped_frames"] == 1


def test_queue_age_is_checked_again_at_read_time(backend, source_factory, monkeypatch):
    now = [100.0]
    monkeypatch.setattr(capture.time, "monotonic", lambda: now[0])
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    stream = backend.streams[0]
    stream.emit(samples(2))
    now[0] += 0.3
    stream.emit(samples(3))
    assert reading(source, 2).result(timeout=1) == frame(3)
    assert source.stats()["dropped_frames"] == 1


def test_overflow_and_backend_errors_are_sanitized(backend, source_factory):
    backend.open_error = RuntimeError("secret backend details")
    source = source_factory()
    error = assert_code(reading(source, 0), "capture_open")
    assert "secret" not in str(error)
    backend.open_error = None
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    backend.streams[0].emit(samples(2), status=True)
    assert_code(reading(source, 1), "capture_overflow")
    assert source.stats()["queued_frames"] == 0


@pytest.mark.parametrize("notify", [True, False])
def test_stopped_device_fails_with_or_without_finished_callback(backend, source_factory, notify):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    stream = backend.streams[0]
    stream.active = False
    if notify:
        stream.options["finished_callback"]()
    assert_code(reading(source, 1), "capture_stopped")


def test_callback_starvation_fails_within_one_second(backend, source_factory, monkeypatch):
    assert capture.READ_TIMEOUT <= 1
    monkeypatch.setattr(capture.time, "monotonic", time.monotonic)
    monkeypatch.setattr(capture, "READ_TIMEOUT", 0.05)
    source = source_factory()
    start = time.monotonic()
    assert_code(reading(source, 0), "capture_timeout")
    assert time.monotonic() - start < 1
    diagnostic = source.diagnostics()
    assert diagnostic["state"] == "failed"
    assert diagnostic["error_code"] == "capture_timeout"
    assert diagnostic["callbacks"] == diagnostic["frames_read"] == 0
    assert diagnostic["last_callback_age_ms"] is None


def test_diagnostics_distinguish_valid_silence_and_stale_callbacks(backend, source_factory, monkeypatch):
    now = [100.0]
    monkeypatch.setattr(capture.time, "monotonic", lambda: now[0])
    backend.initial = [samples(0)]
    source = source_factory()
    assert source.diagnostics()["state"] == "not_started"
    assert reading(source, 0).result(timeout=1) == frame(0)
    assert source.stats()["peak_dbfs"] == float("-inf")
    assert source.diagnostics()["state"] == "capturing"
    stream = backend.streams[0]
    now[0] += 0.1
    stream.emit(samples(0), age=0.4)
    now[0] += 0.02
    stream.emit(samples(0))
    assert reading(source, 1).result(timeout=1) == frame(0)
    diagnostic = source.diagnostics()
    assert diagnostic["callbacks"] == 3
    assert diagnostic["frames_read"] == 2
    assert diagnostic["stale_blocks"] == 1
    assert diagnostic["max_callback_gap_ms"] == pytest.approx(100)
    assert diagnostic["max_backend_age_ms"] == pytest.approx(400)
    assert diagnostic["last_callback_age_ms"] == 0
    assert diagnostic["error_code"] is None
    source.close()
    assert source.diagnostics()["state"] == "closed"


def test_timeout_diagnostics_keep_evidence_of_callbacks_with_stale_timestamps(backend, source_factory, monkeypatch):
    monkeypatch.setattr(capture.time, "monotonic", time.monotonic)
    monkeypatch.setattr(capture, "READ_TIMEOUT", 0.05)
    source = source_factory()
    result = reading(source, 0)
    assert backend.opened.wait(1)
    assert backend.streams[0].started.wait(1)
    backend.streams[0].emit(samples(1), age=0.4)
    assert_code(result, "capture_timeout")
    diagnostic = source.diagnostics()
    assert diagnostic["callbacks"] == diagnostic["stale_blocks"] == 1
    assert diagnostic["frames_read"] == 0
    assert diagnostic["max_backend_age_ms"] == pytest.approx(400)
    assert diagnostic["last_callback_age_ms"] is not None


def test_close_wakes_blocked_reader_and_discards_buffers(backend, source_factory):
    source = source_factory()
    result = reading(source, 0)
    assert backend.opened.wait(1)
    assert backend.streams[0].started.wait(1)
    source.close()
    assert_code(result, "capture_closed")
    assert source.stats()["queued_frames"] == 0
    assert backend.streams[0].closed.wait(1)
    source.close()


@pytest.mark.parametrize("stage", ["open", "start"])
def test_close_during_backend_startup_never_publishes_or_leaks_stream(backend, source_factory, stage):
    gate = threading.Event()
    setattr(backend, stage + "_gate", gate)
    source = source_factory()
    result = reading(source, 0)
    assert backend.opened.wait(1)
    stream = backend.streams[0]
    if stage == "start":
        assert stream.starting.wait(1)
    source.close()
    assert_code(result, "capture_closed")
    assert not stream.closed.is_set()
    gate.set()
    assert stream.closed.wait(1)
    assert not stream.active
    assert source.stats()["queued_frames"] == 0


def test_resampler_delay_and_partial_output_match_continuous_conversion(backend, source_factory):
    # Real converter + fake device: no padding, duplicated output, or premature
    # flush is allowed when soxr temporarily returns fewer than 960 samples.
    blocks = [np.column_stack((np.linspace(-0.3, 0.3, 441, dtype=np.float32),
                               np.linspace(0.1, -0.1, 441, dtype=np.float32)))
              for _ in range(12)]
    reference = PcmConverter(44100, 2)
    expected = b"".join(reference.convert(block) for block in blocks)
    assert len(expected) >= PCM_BYTES * 2
    backend.initial = blocks
    source = source_factory(sample_rate=44100)
    count = len(expected) // PCM_BYTES
    actual = b"".join(reading(source, sequence).result(timeout=1) for sequence in range(count))
    assert actual == expected[:count * PCM_BYTES]
    assert backend.streams[0].options["blocksize"] == 882


def test_context_manager_closes_stream(backend, source_factory):
    backend.initial = [samples(1)]
    with source_factory() as source:
        assert reading(source, 0).result(timeout=1) == frame(1)
    assert backend.streams[0].closed.wait(1)


def test_stale_converted_remainder_is_discarded_at_read_time(backend, source_factory, monkeypatch):
    now = [100.0]
    monkeypatch.setattr(capture.time, "monotonic", lambda: now[0])
    backend.initial = [samples(10, 1920)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(10)
    now[0] += 0.3
    backend.streams[0].emit(samples(30))
    assert reading(source, 2).result(timeout=1) == frame(30)
    assert source.stats()["dropped_frames"] == 1


def test_input_discontinuity_resets_converter_and_keeps_cumulative_clipping(backend, source_factory, monkeypatch):
    backend.initial = [samples(40000)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(32767)
    resets = []
    original_reset = source._converter.reset

    def reset():
        resets.append(True)
        original_reset()

    monkeypatch.setattr(source._converter, "reset", reset)
    stream = backend.streams[0]
    stream.emit(samples(100), age=0.4)
    stream.emit(samples(40000))
    assert reading(source, 2).result(timeout=1) == frame(32767)
    assert resets == [True]
    assert source.stats()["clipped_samples"] == 1920


@pytest.mark.parametrize("fault,code", [("oversize", "capture_overflow"), ("timestamp", "capture_callback")])
def test_invalid_callback_data_fails_without_synthesizing_audio(backend, source_factory, fault, code):
    backend.initial = [samples(1)]
    source = source_factory()
    assert reading(source, 0).result(timeout=1) == frame(1)
    if fault == "oversize":
        backend.streams[0].emit(samples(2, 10000))
    else:
        backend.streams[0].emit(samples(2), age=float("nan"))
    assert_code(reading(source, 1), code)


def test_enumeration_failure_is_sanitized_without_opening(backend, monkeypatch):
    def fail():
        raise RuntimeError("secret backend detail")

    monkeypatch.setattr(backend, "query_devices", fail)
    with pytest.raises(SenderError) as error:
        capture.list_devices()
    assert error.value.code == "capture_devices"
    assert "secret" not in str(error.value)
    assert not backend.streams


def test_raw_resampler_and_pcm_share_one_timeline_bound(backend, source_factory):
    backend.initial = [samples(100, 441) for _ in range(12)]
    source = source_factory(sample_rate=44100)
    assert len(reading(source, 0).result(timeout=1)) == PCM_BYTES
    stream = backend.streams[0]
    new_blocks = [samples(value * 100, 882) for value in range(1, 17)]
    for block in new_blocks:
        stream.emit(block)
    # 120ms startup input + 320ms new input leaves only timeline [240,440)ms.
    # This includes soxr's pending samples and any converted PCM remainder;
    # it is one 200ms budget, rather than a separate budget for each stage.
    assert source.stats()["queued_frames"] == 10
    reference = PcmConverter(44100, 2)
    expected = b"".join(reference.convert(block) for block in new_blocks[-10:])
    assert len(expected) >= PCM_BYTES * 2
    assert reading(source, 12).result(timeout=1) == expected[:PCM_BYTES]
    assert reading(source, 13).result(timeout=1) == expected[PCM_BYTES:2 * PCM_BYTES]
    assert source.stats()["queued_frames"] <= 8
    assert source.stats()["dropped_frames"] == 11


def test_cleanup_attempts_both_operations_and_logs_only_exception_types(caplog):
    calls = []

    class BrokenStream:
        def stop(self):
            calls.append("stop")
            raise RuntimeError("secret device path")

        def close(self):
            calls.append("close")
            raise OSError("secret credential")

    capture.LiveSource._dispose(BrokenStream())
    assert calls == ["stop", "close"]
    assert "Audio input stop failed (RuntimeError)." in caplog.text
    assert "Audio input close failed (OSError)." in caplog.text
    assert "secret" not in caplog.text
