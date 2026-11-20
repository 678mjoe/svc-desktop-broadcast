"""Finite test sources. No audio devices, capture APIs, or system permissions."""

import math
import struct
import threading
import wave
from pathlib import Path
from typing import Protocol

from .errors import InputError
from .protocol import FRAME_SAMPLES, PCM_BYTES, SAMPLE_RATE


class FrameSource(Protocol):
    """Read source-time frame indices, skipping forward without replaying old audio.

    Reads run on a worker thread, one at a time. Return exactly 1920 bytes; pad a
    finite final frame with silence. ``None`` frame_count denotes a live source:
    reads use sequential indices, and the source must discard stale captured
    samples itself rather than replay a backlog. Live wire timestamps follow
    the send clock, independently of device sample indices. The caller owns
    source lifetime and closes it after upload returns.
    """

    frame_count: int | None

    def read_frame(self, sequence: int) -> bytes: ...


class ToneSource:
    """440 Hz, peak -32 dBFS, with 10 ms fades and an explicit finite duration."""

    def __init__(self, seconds: float = 5):
        if not math.isfinite(seconds) or not 0 < seconds <= 30:
            raise InputError("tone_duration", "Tone duration must be greater than 0 and at most 30 seconds.")
        self.sample_count = math.ceil(seconds * SAMPLE_RATE)
        self.frame_count = (self.sample_count + FRAME_SAMPLES - 1) // FRAME_SAMPLES

    def read_frame(self, sequence: int) -> bytes:
        if not 0 <= sequence < self.frame_count:
            raise InputError("source_range", "Requested source frame is outside the audio duration.")
        result = bytearray(PCM_BYTES)
        start = sequence * FRAME_SAMPLES
        count = min(FRAME_SAMPLES, self.sample_count - start)
        amplitude = 32767 * 10 ** (-32 / 20)
        for offset in range(count):
            sample = start + offset
            fade = min(1.0, sample / 480, (self.sample_count - 1 - sample) / 480)
            value = round(amplitude * fade * math.sin(math.tau * 440 * sample / SAMPLE_RATE))
            struct.pack_into("<h", result, offset * 2, value)
        return bytes(result)


class WavSource:
    """Seek to the newest frame; never load the complete file or skipped audio."""

    def __init__(self, path: str | Path):
        self._lock = threading.Lock()
        self._wave: wave.Wave_read | None = None
        try:
            path = Path(path)
            if not path.is_file():
                raise InputError("wav_file", "WAV input must be a readable regular file.")
            audio = wave.open(str(path), "rb")
            self._wave = audio
            if (
                audio.getcomptype() != "NONE"
                or audio.getframerate() != SAMPLE_RATE
                or audio.getnchannels() != 1
                or audio.getsampwidth() != 2
            ):
                raise InputError("wav_format", "WAV must be uncompressed 48000 Hz, mono, signed 16-bit PCM.")
            self.sample_count = audio.getnframes()
            if self.sample_count == 0:
                raise InputError("wav_empty", "WAV input contains no samples.")
            self.frame_count = (self.sample_count + FRAME_SAMPLES - 1) // FRAME_SAMPLES
        except InputError:
            self.close()
            raise
        except (OSError, EOFError, wave.Error, ValueError):
            self.close()
            raise InputError("wav_file", "Cannot read a valid PCM WAV file.") from None

    def read_frame(self, sequence: int) -> bytes:
        if not 0 <= sequence < self.frame_count:
            raise InputError("source_range", "Requested source frame is outside the audio duration.")
        with self._lock:
            if self._wave is None:
                raise InputError("wav_closed", "WAV input is already closed.")
            sample = sequence * FRAME_SAMPLES
            expected = min(FRAME_SAMPLES, self.sample_count - sample) * 2
            try:
                self._wave.setpos(sample)
                pcm = self._wave.readframes(FRAME_SAMPLES)
            except (OSError, EOFError, wave.Error, ValueError):
                raise InputError("wav_read", "Cannot read the next WAV frame.") from None
            if len(pcm) != expected:
                raise InputError("wav_truncated", "WAV sample data is truncated.")
            return pcm.ljust(PCM_BYTES, b"\0")

    def close(self) -> None:
        with self._lock:
            if self._wave is not None:
                self._wave.close()
                self._wave = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
