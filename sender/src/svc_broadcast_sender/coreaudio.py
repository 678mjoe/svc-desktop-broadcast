"""Read selected Mac audio from the signed Core Audio Tap helper."""

from collections import deque
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
import threading
import time

import numpy as np

from .audio import PcmConverter
from .capture import MAX_AGE, READ_TIMEOUT
from .errors import InputError, SenderError
from .protocol import PCM_BYTES


_HEADER = struct.Struct("<4sI")
_BLOCK = struct.Struct("<QQII")
_MAX_FRAMES = 4800
_START_TIMEOUT = 15.0


def bundled_helper() -> Path:
    if sys.platform != "darwin":
        raise InputError("coreaudio_unavailable", "The Core Audio helper requires macOS.")
    relative = Path("SVC Audio Check.app/Contents/MacOS/svc-audio-check")
    packaged = Path(__file__).resolve().parents[1] / relative
    if packaged.is_file():
        return packaged
    path = Path(__file__).resolve().parents[3] / "dist" / relative
    if not path.is_file():
        raise InputError("coreaudio_unavailable", "Build the Mac helper from the project root: sh sender/native/build.sh macos")
    return path


def list_apps(*, identities=False) -> str:
    try:
        action = "list-identities" if identities else "list"
        return subprocess.run([bundled_helper(), action], capture_output=True, check=True, timeout=5,
                              encoding="utf-8", errors="replace").stdout
    except (OSError, subprocess.SubprocessError):
        raise SenderError("coreaudio_apps", "Could not list apps with audio output in Core Audio.") from None


class CoreAudioSource:
    """Bounded stereo input, adapted to the current mono upload on demand."""

    frame_count = None

    def __init__(self, pids, *, selectors=None, helper=None):
        self.pids = tuple(pids or ())
        if (not 1 <= len(self.pids) <= 4
                or any(type(pid) is not int or not 1 < pid <= 0x7FFFFFFF for pid in self.pids)
                or len(set(self.pids)) != len(self.pids)):
            raise InputError("coreaudio_selection", "Select 1–4 distinct app PIDs from the Mac audio app list.")
        self._helper = bundled_helper() if helper is None else helper
        self._selectors = tuple(selectors) if selectors is not None else None
        if self._selectors is not None and (len(self._selectors) != len(self.pids) or any(
                not re.fullmatch(rf"{pid}:[0-9]{{1,20}}:[0-9]{{1,6}}", selector)
                for pid, selector in zip(self.pids, self._selectors))):
            raise InputError("coreaudio_selection", "Invalid app identity. List and select the apps again.")
        self._condition = threading.Condition()
        self._process = None
        self._reader = None
        self._closed = False
        self._failure = None
        self._rate = None
        self._raw = deque()
        self._queued_samples = 0
        self._blocks_seen = 0
        self._dropped_samples = 0
        self._input_end = 0
        self._last_block = None
        self._sequence = -1
        self._started_at = None
        self._converter = None
        self._pending = bytearray()
        self._pending_at = None
        self._converted_end = None
        self._peak = self._rms = self._session_peak = -math.inf
        self._clipped = 0

    def _open(self):
        self._started_at = time.monotonic()
        action, targets = ("stream-checked", self._selectors) if self._selectors is not None else (
            "stream", map(str, self.pids))
        try:
            self._process = subprocess.Popen(
                [self._helper, action, ",".join(targets)],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            )
        except OSError:
            raise SenderError("coreaudio_start", "Could not launch the bundled Core Audio helper.") from None
        self._reader = threading.Thread(target=self._read_pipe, args=(self._process.stdout,),
                                        daemon=True, name="svc-coreaudio-pipe")
        self._reader.start()

    def _discard_old(self, now):
        while self._raw and (now - self._raw[0][0] >= MAX_AGE or self._queued_samples > self._rate * MAX_AGE):
            _, _, samples = self._raw.popleft()
            self._queued_samples -= len(samples)
            self._dropped_samples += len(samples)

    def _read_pipe(self, stream):
        failure = ("coreaudio_stopped", "The Mac audio helper stopped. Check the selected apps and macOS system audio permission.")
        try:
            header = stream.read(_HEADER.size)
            if not header:
                return
            magic, rate = _HEADER.unpack(header)
            if magic != b"SVCT" or rate not in (44100, 48000):
                raise ValueError
            converter = PcmConverter(rate, 2, 20 * math.log10(0.8 / len(self.pids)))
            with self._condition:
                self._rate = rate
                self._converter = converter
                self._condition.notify_all()
            while header := stream.read(_BLOCK.size):
                timestamp, start, frames, reserved = _BLOCK.unpack(header)
                if not 0 < frames <= _MAX_FRAMES or reserved or start < self._input_end:
                    raise ValueError
                data = stream.read(frames * 8)
                if len(data) != frames * 8:
                    raise ValueError
                now = time.monotonic()
                captured_at = timestamp / 1_000_000_000 - frames / rate
                if not timestamp or captured_at > now + 0.02:
                    raise ValueError
                samples = np.frombuffer(data, dtype="<f4").reshape(frames, 2)
                with self._condition:
                    if self._closed:
                        return
                    self._blocks_seen += 1
                    self._last_block = now
                    self._dropped_samples += start - self._input_end
                    self._input_end = start + frames
                    self._raw.append((captured_at, start, samples))
                    self._queued_samples += frames
                    self._discard_old(now)
                    self._condition.notify_all()
        except (ValueError, OSError, struct.error):
            failure = ("coreaudio_pipe", "The Mac audio helper returned an invalid format or audio block.")
        finally:
            with self._condition:
                self._failure = failure
                self._condition.notify_all()

    def read_frame(self, sequence):
        deadline = time.monotonic() + READ_TIMEOUT
        with self._condition:
            if self._closed:
                raise SenderError("capture_closed", "Mac audio capture is closed.")
            if type(sequence) is not int or sequence <= self._sequence or (self._sequence == -1 and sequence != 0):
                raise InputError("source_range", "Live capture requires frame zero followed by increasing frame indices.")
            self._sequence = sequence
            if self._process is None:
                self._open()
        while True:
            with self._condition:
                if self._closed:
                    raise SenderError("capture_closed", "Mac audio capture is closed.")
                if self._failure:
                    raise SenderError(*self._failure)
                now = time.monotonic()
                if self._rate is None:
                    if now - self._started_at >= _START_TIMEOUT:
                        raise SenderError("coreaudio_start_timeout", "Mac audio capture did not start. Check system audio permission.", 5)
                    # The accepted upload stays alive while the private tap opens.
                    return bytes(PCM_BYTES)
                self._discard_old(now)
                if self._pending_at is not None and now - self._pending_at >= MAX_AGE:
                    self._reset_conversion()
                if len(self._pending) >= PCM_BYTES:
                    frame = bytes(self._pending[:PCM_BYTES])
                    del self._pending[:PCM_BYTES]
                    return frame
                if now >= deadline:
                    raise SenderError("coreaudio_timeout", "Mac audio capture did not deliver a fresh frame.", 5)
                if not self._raw:
                    self._condition.wait(deadline - now)
                    continue
                captured_at, start, samples = self._raw.popleft()
                self._queued_samples -= len(samples)

            # SoXR state may cross adjacent blocks, never a dropped gap.
            if self._converted_end is not None and self._converted_end != start:
                self._reset_conversion()
            if not self._pending:
                self._pending_at = captured_at
            clips = self._converter.clipped_samples
            self._pending.extend(self._converter.convert(samples))
            self._converted_end = start + len(samples)
            with self._condition:
                self._peak, self._rms = self._converter.peak_dbfs, self._converter.rms_dbfs
                self._session_peak = max(self._session_peak, self._peak)
                self._clipped += self._converter.clipped_samples - clips

    def _reset_conversion(self):
        self._pending.clear()
        self._pending_at = self._converted_end = None
        self._converter.reset()

    @property
    def ready(self):
        with self._condition:
            return self._rate is not None

    def stats(self):
        with self._condition:
            return {"peak_dbfs": self._peak, "rms_dbfs": self._rms, "clipped_samples": self._clipped,
                    "dropped_frames": self._dropped_samples * 50 // (self._rate or 48000),
                    "queued_frames": self._queued_samples * 50 // (self._rate or 48000)}

    def diagnostics(self):
        with self._condition:
            return {"source": "coreaudio_process_tap", "pids": self.pids, "rate": self._rate,
                    "blocks": self._blocks_seen, "dropped_samples": self._dropped_samples,
                    "last_block_age_ms": None if self._last_block is None else
                    round((time.monotonic() - self._last_block) * 1000, 1),
                    "pcm_peak_dbfs": None if self._session_peak == -math.inf else round(self._session_peak, 2)}

    def verify_check_signal(self):
        with self._condition:
            if self._session_peak == -math.inf:
                raise SenderError("coreaudio_input_silent", "The selected Mac apps did not produce audible samples.", 5)

    def close(self):
        with self._condition:
            if self._closed:
                return
            self._closed = True
            self._raw.clear()
            self._queued_samples = 0
            process = self._process
            self._condition.notify_all()
        if process is None:
            return
        process.terminate()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        self._reader.join(timeout=1)
        process.stdout.close()
