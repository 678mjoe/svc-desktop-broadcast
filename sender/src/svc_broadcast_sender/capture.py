"""Lazy, bounded input capture for an explicitly selected virtual audio device."""

from collections import deque
from dataclasses import dataclass
import logging
import math
import threading
import time

from .audio import PcmConverter
from .errors import InputError, SenderError
from .protocol import FRAME_SAMPLES, PCM_BYTES, SAMPLE_RATE


MAX_AGE = 0.2
READ_TIMEOUT = 0.9
_LOGGER = logging.getLogger(__name__)


def _backend():
    try:
        import sounddevice
        return sounddevice
    except Exception:
        raise SenderError("capture_backend", "The audio capture backend is unavailable.") from None


@dataclass(frozen=True)
class CaptureDevice:
    index: int
    name: str
    hostapi: str
    input_channels: int
    output_channels: int
    default_samplerate: float


def list_devices() -> list[CaptureDevice]:
    """Enumerate metadata only; this never constructs an input stream."""
    backend = _backend()
    try:
        hosts = backend.query_hostapis()
        return [
            CaptureDevice(
                index, str(info["name"]), str(hosts[info["hostapi"]]["name"]),
                int(info["max_input_channels"]), int(info["max_output_channels"]),
                float(info["default_samplerate"]),
            )
            for index, info in enumerate(backend.query_devices())
        ]
    except Exception:
        raise SenderError("capture_devices", "Cannot enumerate audio capture devices.") from None


def resolve_device(name: str, hostapi: str | None = None) -> CaptureDevice:
    matches = [device for device in list_devices()
               if device.input_channels > 0 and device.name == name
               and (hostapi is None or device.hostapi == hostapi)]
    if not matches:
        raise InputError("capture_missing", "No input device matches the exact device and host API names.")
    if len(matches) != 1:
        raise InputError("capture_ambiguous", "Multiple input devices match; select an unambiguous host API and device name.")
    return matches[0]


@dataclass(frozen=True)
class _Block:
    samples: object
    start: int
    count: int
    captured_at: float


class LiveSource:
    """One reader consumes mono 48 kHz frames; callbacks only queue float samples.

    The caller must wait for server readiness before requesting frame zero.
    Sequence numbers refer to the original capture timeline, including input
    already discarded for freshness, so a late request never skips audio twice.
    """

    frame_count = None

    def __init__(self, device: CaptureDevice, sample_rate: int = 48000,
                 channels: int = 2, gain_db: float = 0):
        if not isinstance(device, CaptureDevice) or not device.name or not device.hostapi:
            raise InputError("capture_device", "Select an input device by its exact name and host API.")
        if type(sample_rate) is not int or sample_rate <= 0:
            raise InputError("capture_rate", "Capture sample rate must be a positive integer.")
        if type(channels) is not int or channels not in (1, 2) or channels > device.input_channels:
            raise InputError("capture_channels", "Capture requires one or two supported input channels.")
        self.device = device
        self.sample_rate = sample_rate
        self.channels = channels
        self._converter = PcmConverter(sample_rate, channels, gain_db)
        self._condition = threading.Condition()
        self._reader = threading.Lock()
        self._raw = deque()
        self._pcm = bytearray()
        self._stream = None
        self._opening = False
        self._started = False
        self._closed = False
        self._error = None
        self._last_sequence = -1
        self._input_end = 0
        self._processed_end = 0
        self._floor = 0
        self._position = 0
        self._origin = None
        self._dropped_samples = 0
        self._metrics = {"peak_dbfs": float("-inf"), "rms_dbfs": float("-inf"), "clipped_samples": 0}
        self._max_samples = max(1, int(sample_rate * MAX_AGE))
        self._callbacks = 0
        self._frames_read = 0
        self._last_callback = None
        self._max_callback_gap = 0.0
        self._max_backend_age = 0.0
        self._stale_blocks = 0

    def _fail_locked(self, code, message):
        if self._error is None and not self._closed:
            self._error = SenderError(code, message)
        self._raw.clear()
        self._pcm.clear()
        self._condition.notify_all()

    def _check_locked(self):
        if self._closed:
            raise SenderError("capture_closed", "Audio capture is closed.")
        if self._error is not None:
            raise self._error

    def _trim_locked(self, now):
        while self._raw and (self._raw[0].start < self._floor
                             or now - self._raw[0].captured_at > MAX_AGE):
            block = self._raw.popleft()
            if now - block.captured_at > MAX_AGE:
                self._stale_blocks += 1
            self._floor = max(self._floor, block.start + block.count)

    def _callback(self, indata, frames, timing, status):
        # No conversion, backend operations, logging, or network work here.
        now = time.monotonic()
        with self._condition:
            if self._closed or self._error is not None:
                return
            self._callbacks += 1
            if self._last_callback is not None:
                self._max_callback_gap = max(self._max_callback_gap, now - self._last_callback)
            self._last_callback = now
            if status:
                self._fail_locked("capture_overflow", "The input backend reported a capture discontinuity.")
                return
            if frames <= 0 or frames > self._max_samples:
                self._fail_locked("capture_overflow", "The input backend exceeded the capture buffer bound.")
                return
            try:
                backend_age = float(timing.currentTime) - float(timing.inputBufferAdcTime)
                if not math.isfinite(backend_age):
                    raise ValueError
                age = max(frames / self.sample_rate, backend_age)
                self._max_backend_age = max(self._max_backend_age, age)
                if indata.shape != (frames, self.channels):
                    raise ValueError
                block = _Block(indata.copy(), self._input_end, frames, now - age)
            except Exception:
                self._fail_locked("capture_callback", "The input backend supplied invalid capture data.")
                return
            self._input_end += frames
            self._floor = max(self._floor, self._input_end - self._max_samples)
            self._raw.append(block)
            self._trim_locked(now)
            self._condition.notify_all()

    def _finished(self):
        with self._condition:
            self._fail_locked("capture_stopped", "The audio input stream stopped.")

    @staticmethod
    def _dispose(stream):
        # Backend calls must never run under the condition lock.
        try:
            stream.stop()
        except Exception as error:
            _LOGGER.warning("Audio input stop failed (%s).", type(error).__name__)
        try:
            stream.close()
        except Exception as error:
            _LOGGER.warning("Audio input close failed (%s).", type(error).__name__)

    def _open(self):
        stream = None
        try:
            current = resolve_device(self.device.name, self.device.hostapi)
            if current.input_channels < self.channels:
                raise InputError("capture_channels", "The selected input no longer supports the requested channels.")
            with self._condition:
                self._check_locked()
            stream = _backend().InputStream(
                device=current.index, samplerate=self.sample_rate, channels=self.channels,
                dtype="float32", blocksize=max(1, round(self.sample_rate * 0.02)),
                callback=self._callback, finished_callback=self._finished,
            )
            with self._condition:
                self._check_locked()
            stream.start()
            with self._condition:
                self._check_locked()
                self._stream = stream
                self._started = True
                stream = None
                self._condition.notify_all()
        except Exception as error:
            with self._condition:
                if isinstance(error, SenderError):
                    self._fail_locked(error.code, str(error))
                else:
                    self._fail_locked("capture_open", "Cannot open the selected audio input with the requested format.")
        finally:
            if stream is not None:
                self._dispose(stream)

    def _output_sample(self, input_sample):
        return (input_sample * SAMPLE_RATE + self.sample_rate - 1) // self.sample_rate

    def _discard_to_locked(self, position):
        if position > self._position:
            self._dropped_samples += position - self._position
            self._position = position

    def read_frame(self, sequence: int) -> bytes:
        with self._reader:
            return self._read_frame(sequence)

    def _read_frame(self, sequence):
        deadline = time.monotonic() + READ_TIMEOUT
        with self._condition:
            self._check_locked()
            if type(sequence) is not int or sequence <= self._last_sequence or (self._last_sequence < 0 and sequence != 0):
                raise InputError("source_range", "Live capture requires frame zero followed by increasing frame indices.")
            self._last_sequence = sequence
            if not self._opening:
                self._opening = True
                threading.Thread(target=self._open, name="svc-capture-open", daemon=True).start()
        target = sequence * FRAME_SAMPLES
        clipped_before_reset = self._metrics["clipped_samples"] - self._converter.clipped_samples
        while True:
            reset = False
            block = None
            # Poll outside the condition: backend properties may call PortAudio.
            with self._condition:
                stream = self._stream
            if stream is not None:
                try:
                    active = stream.active
                except Exception:
                    active = False
                if not active:
                    with self._condition:
                        self._fail_locked("capture_stopped", "The audio input stream stopped.")
            with self._condition:
                self._check_locked()
                now = time.monotonic()
                if now >= deadline:
                    self._fail_locked("capture_timeout", "Audio capture did not provide a fresh frame in time.")
                    self._check_locked()
                self._trim_locked(now)
                if self._output_sample(self._floor) > self._position:
                    self._pcm.clear()
                    self._discard_to_locked(self._output_sample(max(self._floor, self._processed_end)))
                    self._origin = None
                    reset = True
                elif self._pcm and self._origin is not None and now - (self._origin + self._position / SAMPLE_RATE) > MAX_AGE:
                    self._pcm.clear()
                    self._discard_to_locked(self._output_sample(self._processed_end))
                    self._origin = None
                    reset = True
                if not reset:
                    skip = min(max(0, target - self._position), len(self._pcm) // 2)
                    if skip:
                        del self._pcm[:skip * 2]
                        self._discard_to_locked(self._position + skip)
                    if self._started and len(self._pcm) >= PCM_BYTES:
                        frame = bytes(self._pcm[:PCM_BYTES])
                        del self._pcm[:PCM_BYTES]
                        self._position += FRAME_SAMPLES
                        self._frames_read += 1
                        return frame
                    if self._raw:
                        block = self._raw.popleft()
                    else:
                        remaining = deadline - now
                        # Wake periodically to detect a backend that stopped silently.
                        self._condition.wait(min(remaining, 0.05))
            if reset:
                clipped_before_reset = self._metrics["clipped_samples"]
                try:
                    self._converter.reset()
                except Exception:
                    with self._condition:
                        self._fail_locked("capture_convert", "Cannot reset the captured audio converter.")
                continue
            if block is not None:
                try:
                    pcm = self._converter.convert(block.samples)
                    metrics = {"peak_dbfs": self._converter.peak_dbfs,
                               "rms_dbfs": self._converter.rms_dbfs,
                               "clipped_samples": clipped_before_reset + self._converter.clipped_samples}
                except Exception:
                    with self._condition:
                        self._fail_locked("capture_convert", "Cannot convert the captured audio format.")
                    continue
                with self._condition:
                    self._check_locked()
                    if self._origin is None:
                        self._origin = block.captured_at - block.start / self.sample_rate
                    self._processed_end = block.start + block.count
                    self._pcm.extend(pcm)
                    self._metrics = metrics

    def stats(self) -> dict:
        with self._condition:
            start = max(self._position, self._output_sample(self._floor))
            queued = max(0, self._output_sample(self._input_end) - start)
            if self._closed or self._error is not None:
                queued = 0
            return {**self._metrics, "dropped_frames": self._dropped_samples // FRAME_SAMPLES,
                    "queued_frames": (queued + FRAME_SAMPLES - 1) // FRAME_SAMPLES}

    def diagnostics(self) -> dict:
        """Timing and counters only: never audio samples or backend exceptions.

        An all-zero device stream still has callbacks and readable frames.
        Inspect these alongside level statistics before attributing silence to
        OBS, a stopped device, stale timestamps, or transport failure.
        """
        with self._condition:
            now = time.monotonic()
            if self._error is not None:
                state = "failed"
            elif self._closed:
                state = "closed"
            else:
                state = "capturing" if self._started else "starting" if self._opening else "not_started"
            return {
                "state": state,
                "error_code": self._error.code if self._error is not None else None,
                "callbacks": self._callbacks,
                "frames_read": self._frames_read,
                "last_callback_age_ms": None if self._last_callback is None else
                    round(max(0.0, now - self._last_callback) * 1000, 3),
                "max_callback_gap_ms": round(self._max_callback_gap * 1000, 3),
                "max_backend_age_ms": round(self._max_backend_age * 1000, 3),
                "stale_blocks": self._stale_blocks,
            }

    def close(self) -> None:
        with self._condition:
            self._closed = True
            self._raw.clear()
            self._pcm.clear()
            stream, self._stream = self._stream, None
            self._condition.notify_all()
        if stream is not None:
            self._dispose(stream)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
