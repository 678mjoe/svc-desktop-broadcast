"""Bounded timestamped capture from the bundled Windows process-loopback helper."""

from dataclasses import dataclass
from pathlib import Path
import re
import struct
import subprocess
import sys
import threading
import time
import unicodedata

import numpy as np

from .audio import PcmConverter
from .errors import InputError, SenderError
from .protocol import FRAME_SAMPLES, PCM_BYTES


_RATE = 44100
_BLOCK = _RATE // 50
_CAPACITY = _RATE // 5
_PLAYOUT_DELAY = _BLOCK * 4
# Four ready blocks plus the 80 ms alignment window fit the 200 ms ring.
_MAX_READY_BLOCKS = 4
_RECORD = struct.Struct("<QII")
_START_TIMEOUT = 45
_READ_TIMEOUT = 0.9


def _sample_time(qpc_100ns: int) -> int:
    return (qpc_100ns * _RATE + 5_000_000) // 10_000_000


def bundled_helper() -> Path:
    # The Windows ZIP places the helper next to the Python package.
    path = Path(__file__).resolve().parent.parent / "svc-wasapi-check.exe"
    if sys.platform != "win32" or not path.is_file():
        raise InputError("wasapi_unavailable", "Use the Windows 11 x64 native audio test bundle.")
    return path


@dataclass(frozen=True)
class AppTarget:
    pid: int
    created: int
    label: str
    active_session: bool = False

    @property
    def selector(self) -> str:
        return f"{self.pid}:{self.created}"


def list_apps(*, excluded_pid=None) -> list[AppTarget]:
    """Enumerate visible app roots without opening a capture stream."""
    helper = bundled_helper()
    arguments = [helper, "apps"]
    if excluded_pid is not None:
        arguments.append(str(excluded_pid))
    try:
        result = subprocess.run(arguments, capture_output=True, check=True,
                                timeout=8, encoding="utf-8", errors="strict",
                                creationflags=subprocess.CREATE_NO_WINDOW)
        targets = []
        for line in result.stdout.split("\n"):
            if not line:
                continue
            pid, created, kind, label = line.split("\t", 3)
            if kind not in ("A", "W") or not re.fullmatch(r"[0-9]{1,10}:[0-9]{1,20}", f"{pid}:{created}"):
                raise ValueError
            pid, created = int(pid), int(created)
            if not 4 < pid <= 0xFFFFFFFF or not 0 < created <= 0xFFFFFFFFFFFFFFFF:
                raise ValueError
            label = "".join(ch for ch in label if unicodedata.category(ch)[0] != "C"
                            and ch not in "\u2028\u2029").strip()
            targets.append(AppTarget(pid, created, label or f"PID {pid}", kind == "A"))
        return sorted(targets, key=lambda target: (not target.active_session, target.label.casefold(), target.pid))
    except (OSError, ValueError, subprocess.SubprocessError):
        raise SenderError("wasapi_apps", "Could not list Windows apps. Reopen the player and try again.") from None


def source_from_selection(pids, selectors, *, excluded_pid=None):
    if selectors is None:
        return WasapiSource(pids, excluded_pid=excluded_pid)
    if not all(re.fullmatch(r"[0-9]{1,10}:[0-9]{1,20}", value) for value in selectors):
        raise InputError("wasapi_selection", "Invalid Windows app selection. Select the apps again.")
    targets = [tuple(map(int, value.split(":"))) for value in selectors]
    return WasapiSource([pid for pid, _ in targets],
                        created=[created for _, created in targets], excluded_pid=excluded_pid)


class _MixBuffer:
    """A fixed-size audio timeline keyed by the clock shared by WASAPI streams."""

    def __init__(self, count: int):
        self.samples = np.zeros((count, _CAPACITY, 2), dtype=np.int16)
        self.cursor = None
        self.horizon = None
        self.dropped_frames = 0

    def tick(self, now: int) -> bool:
        if self.horizon is None:
            self.cursor = now - _PLAYOUT_DELAY
        if self.horizon is None or now > self.horizon:
            self.horizon = now
            return True
        return False

    def ready_blocks(self) -> int:
        if self.cursor is None:
            return 0
        return max(0, (self.horizon - _PLAYOUT_DELAY - self.cursor) // _BLOCK)

    def add(self, index: int, start: int, audio: np.ndarray) -> tuple[int, int, int]:
        """Return counts of samples written, too old, and too far ahead."""
        if self.cursor is None:
            return 0, 0, 0
        count = len(audio)
        late = min(count, max(0, self.cursor - start))
        early = min(count, max(0, start + count - self.cursor - _CAPACITY))
        written = count - late - early
        if written:
            positions = np.arange(start + late, start + count - early) % _CAPACITY
            self.samples[index, positions] = audio[late:count - early]
        return written, late, early

    def pop(self) -> np.ndarray | None:
        ready = self.ready_blocks()
        if not ready:
            return None
        # Absorb normal timer jitter. Resetting on every two-block wake can
        # starve SoXR, which needs several blocks to emit one full PCM frame.
        skip = max(0, ready - _MAX_READY_BLOCKS)
        if skip:
            count = skip * _BLOCK
            if count >= _CAPACITY:
                self.samples.fill(0)
            else:
                self.samples[:, np.arange(self.cursor, self.cursor + count) % _CAPACITY] = 0
            self.cursor += count
            self.dropped_frames += skip
        positions = np.arange(self.cursor, self.cursor + _BLOCK) % _CAPACITY
        chunk = self.samples[:, positions].mean(axis=0, dtype=np.float32)
        self.samples[:, positions] = 0
        self.cursor += _BLOCK
        # Fixed gain across all chosen tracks, even while some of them pause.
        # Leave additional headroom for conversion from 44.1 to 48 kHz.
        return chunk * np.float32(0.80 / 32768)


class WasapiSource:
    """Start capture lazily when the check or accepted upload reads frame zero."""

    frame_count = None

    def __init__(self, pids, *, created=None, excluded_pid=None, helper: Path | None = None):
        self.pids = tuple(pids)
        if (not 1 <= len(self.pids) <= 4
                or any(type(pid) is not int or not 4 < pid <= 0xFFFFFFFF for pid in self.pids)
                or len(set(self.pids)) != len(self.pids)):
            raise InputError("wasapi_selection", "Select 1–4 distinct Windows app process IDs.")
        if created is not None and (len(created) != len(self.pids)
                                    or any(not 0 < value <= 0xFFFFFFFFFFFFFFFF for value in created)):
            raise InputError("wasapi_selection", "Invalid Windows app selection. Select the apps again.")
        self._selection = (",".join(f"{pid}:{value}" for pid, value in zip(self.pids, created))
                           if created is not None else ",".join(map(str, self.pids)))
        self._excluded_pid = excluded_pid
        self._helper = bundled_helper() if helper is None else helper
        self._condition = threading.Condition()
        self._buffer = _MixBuffer(len(self.pids))
        self._converter = PcmConverter(_RATE, 2)
        self._process = None
        self._reader = None
        self._errors = None
        self._closed = False
        self._failure = None
        self._detail = None
        self._app_changed = False
        self._last_sequence = -1
        self._pending = bytearray()
        self._stats = {"peak_dbfs": -float("inf"), "rms_dbfs": -float("inf"), "clipped_samples": 0}
        self._packet_counts = [0] * len(self.pids)
        self._signal_packets = [0] * len(self.pids)
        self._placed_signal_packets = [0] * len(self.pids)
        self._late_signal_samples = [0] * len(self.pids)
        self._early_signal_samples = [0] * len(self.pids)
        self._raw_peak = [0] * len(self.pids)
        self._mixed_peak = 0.0
        self._pcm_peak_dbfs = -float("inf")
        self._last_heartbeat = None
        self._last_clock_progress = None
        self._max_clock_step = 0
        self._mixed_blocks = 0
        self._converter_resets = 0
        self._started_at = None

    def _open(self):
        arguments = [self._helper, "stream", self._selection]
        if self._excluded_pid is not None:
            arguments.append(str(self._excluded_pid))
        try:
            self._process = subprocess.Popen(
                arguments,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            )
        except OSError:
            raise SenderError("wasapi_start", "Could not launch the bundled Windows audio capture.") from None
        self._started_at = time.monotonic()
        self._reader = threading.Thread(target=self._read_records, daemon=True, name="svc-wasapi-audio")
        self._errors = threading.Thread(target=self._read_errors, daemon=True, name="svc-wasapi-status")
        self._errors.start()
        self._reader.start()

    def _read_errors(self):
        # Only retain a numeric HRESULT. No external text reaches diagnostics.
        for line in self._process.stderr:
            if line.startswith(b"APP_CHANGED "):
                with self._condition:
                    self._app_changed = True
            match = re.search(rb"HRESULT (0x[0-9A-Fa-f]{8})", line)
            if match:
                with self._condition:
                    self._detail = match[1].decode("ascii")

    def _read_records(self):
        stream = self._process.stdout
        failure = "Windows audio capture ended. Check that all selected apps are still running."
        try:
            while header := stream.read(_RECORD.size):
                timestamp, index, frames = _RECORD.unpack(header)
                position = _sample_time(timestamp)
                if not index and not frames:
                    with self._condition:
                        if self._closed:
                            return
                        now = time.monotonic()
                        previous = self._buffer.horizon
                        if self._buffer.tick(position):
                            self._last_clock_progress = now
                            if previous is not None:
                                self._max_clock_step = max(self._max_clock_step, position - previous)
                        self._last_heartbeat = now
                        self._condition.notify_all()
                    continue
                if not 1 <= index <= len(self.pids) or not 1 <= frames <= _CAPACITY:
                    raise ValueError
                data = stream.read(frames * 4)
                if len(data) != frames * 4:
                    raise ValueError
                audio = np.frombuffer(data, dtype="<i2").reshape(-1, 2)
                peak = max(int(audio.max()), -int(audio.min()))
                track = index - 1
                with self._condition:
                    if self._closed:
                        return
                    self._packet_counts[track] += 1
                    written, late, early = self._buffer.add(track, position, audio)
                    if peak:
                        self._signal_packets[track] += 1
                        self._raw_peak[track] = max(peak, self._raw_peak[track])
                        self._late_signal_samples[track] += late
                        self._early_signal_samples[track] += early
                        if written and audio[late:late + written].any():
                            self._placed_signal_packets[track] += 1
        except (OSError, ValueError, struct.error):
            failure = "Windows audio capture returned an incomplete or invalid packet."
        finally:
            self._errors.join(timeout=0.1)
            with self._condition:
                self._failure = failure
                self._condition.notify_all()

    def read_frame(self, sequence: int) -> bytes:
        with self._condition:
            if self._closed:
                raise SenderError("capture_closed", "Windows audio capture is closed.")
            if type(sequence) is not int or sequence <= self._last_sequence or (self._last_sequence == -1 and sequence != 0):
                raise InputError("source_range", "Live capture requires frame zero followed by increasing frame indices.")
            self._last_sequence = sequence
            first = self._process is None
            if first:
                self._open()
        deadline = time.monotonic() + _READ_TIMEOUT
        while True:
            with self._condition:
                if self._closed:
                    raise SenderError("capture_closed", "Windows audio capture is closed.")
                if self._failure:
                    if self._app_changed:
                        raise InputError("wasapi_app_changed", "A selected app restarted. Select it again from the list.")
                    detail = f" (HRESULT {self._detail})" if self._detail else ""
                    raise SenderError("wasapi_stopped", self._failure + detail)
                now = time.monotonic()
                if self._last_heartbeat is None:
                    if now - self._started_at >= _START_TIMEOUT:
                        raise SenderError("wasapi_start_timeout", "Windows audio capture did not finish starting.", 5)
                    # The server already accepted START and times out after two
                    # seconds without frames. Keep it live during WASAPI activation.
                    return bytes(PCM_BYTES)
                if now - self._last_heartbeat >= _READ_TIMEOUT:
                    raise SenderError("wasapi_timeout", "The Windows audio helper stopped sending heartbeats.", 5)
                if now - self._last_clock_progress >= _READ_TIMEOUT:
                    raise SenderError("wasapi_clock", "Windows audio timestamps stopped advancing.", 5)
                remaining = deadline - now
                if remaining <= 0:
                    raise SenderError("wasapi_frame_timeout", "Windows audio heartbeats continued, but mixing did not produce a frame.", 5)
                if len(self._pending) >= PCM_BYTES and self._buffer.ready_blocks() <= _MAX_READY_BLOCKS:
                    frame = bytes(self._pending[:PCM_BYTES])
                    del self._pending[:PCM_BYTES]
                    return frame
                previous_drops = self._buffer.dropped_frames
                block = self._buffer.pop()
                skipped = self._buffer.dropped_frames > previous_drops
                if block is None:
                    self._condition.wait(remaining)
                    continue
                self._mixed_blocks += 1
                self._converter_resets += skipped
                self._mixed_peak = max(self._mixed_peak, float(np.max(np.abs(block))))
            if skipped:
                self._pending.clear()
                previous_clips = self._stats["clipped_samples"]
                self._converter.reset()
            else:
                previous_clips = self._stats["clipped_samples"] - self._converter.clipped_samples
            self._pending.extend(self._converter.convert(block))
            with self._condition:
                self._pcm_peak_dbfs = max(self._pcm_peak_dbfs, self._converter.peak_dbfs)
                self._stats = {"peak_dbfs": self._converter.peak_dbfs, "rms_dbfs": self._converter.rms_dbfs,
                               "clipped_samples": previous_clips + self._converter.clipped_samples}

    def stats(self):
        with self._condition:
            return {**self._stats, "dropped_frames": self._buffer.dropped_frames,
                    "queued_frames": self._buffer.ready_blocks()}

    def diagnostics(self):
        with self._condition:
            return {"source": "wasapi_process_loopback", "pids": self.pids, "packets": list(self._packet_counts),
                    "signal_packets": list(self._signal_packets), "raw_peak_s16": list(self._raw_peak),
                    "placed_signal_packets": list(self._placed_signal_packets),
                    "late_signal_samples": list(self._late_signal_samples),
                    "early_signal_samples": list(self._early_signal_samples),
                    "mixed_peak": round(self._mixed_peak, 6),
                    "pcm_peak_dbfs": None if self._pcm_peak_dbfs == -float("inf") else round(self._pcm_peak_dbfs, 2),
                    "heartbeat_age_ms": None if self._last_heartbeat is None else
                    round((time.monotonic() - self._last_heartbeat) * 1000, 1),
                    "max_clock_step_ms": round(self._max_clock_step * 1000 / _RATE, 1),
                    "ready_blocks": self._buffer.ready_blocks(), "mixed_blocks": self._mixed_blocks,
                    "converter_resets": self._converter_resets, "dropped_frames": self._buffer.dropped_frames,
                    "hresult": self._detail}

    def verify_check_signal(self) -> None:
        """Distinguish all-silent input from losing a signal in the mixer."""
        diagnostics = self.diagnostics()
        if not any(diagnostics["signal_packets"]):
            raise SenderError("wasapi_input_silent", "Windows returned no audible samples for the selected apps. "
                              "Select the audio-session entry while the player is playing.", 5)
        if diagnostics["pcm_peak_dbfs"] is None:
            raise SenderError("wasapi_mix_silent", "Windows returned nonzero samples, but the final PCM output is silent.", 5)

    @property
    def ready(self) -> bool:
        with self._condition:
            return self._last_heartbeat is not None

    def close(self):
        with self._condition:
            if self._closed:
                return
            self._closed = True
            process = self._process
            self._condition.notify_all()
        if process is None:
            return
        process.stdin.close()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        self._reader.join(timeout=1)
        self._errors.join(timeout=1)
        process.stdout.close()
        process.stderr.close()
