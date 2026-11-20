"""Streaming adaptation from selected capture channels to version-one mono PCM."""

import math
from numbers import Real

import numpy as np
import soxr

from .errors import InputError
from .protocol import SAMPLE_RATE


class PcmConverter:
    """Convert float32 capture blocks without changing source channel metadata.

    Version one averages stereo L/R before resampling to 48000 Hz. MQ is
    libsoxr's 16-bit, medium-rolloff preset; pending resampler output is bounded
    to 2400 samples (50 ms), separately from capture and transport buffering.
    See https://python-soxr.readthedocs.io/en/latest/soxr.html and libsoxr's
    ``soxr.h`` quality presets. A stream persists across calls until final/reset.

    Meters describe the latest nonempty emitted PCM block after gain, clipping,
    and quantization, normalized by 32768. Silence and initial/reset meters are
    negative infinity; calls emitting no samples leave the meters unchanged.
    ``clipped_samples`` counts samples outside [-1, 32767/32768] since reset.
    ``reset`` discards pending audio, clears meters and clipping counts, and
    allows another continuous sequence after finalization or a discontinuity.
    Instances are used by one processing worker, not by the capture callback.
    """

    MAX_DELAY_SAMPLES = 2400

    def __init__(self, sample_rate: int, channels: int, gain_db: float = 0):
        if type(sample_rate) is not int or sample_rate not in (44100, 48000):
            raise InputError("audio_rate", "Capture sample rate must be 44100 or 48000 Hz.")
        if type(channels) is not int or channels not in (1, 2):
            raise InputError("audio_channels", "Capture must provide one or two channels.")
        if (
            isinstance(gain_db, bool)
            or not isinstance(gain_db, Real)
            or not -60 <= gain_db <= 12
            or not math.isfinite(gain_db)
        ):
            raise InputError("audio_gain", "Capture gain must be finite and between -60 and 12 dB.")
        self.sample_rate = sample_rate
        self.channels = channels
        self.gain_db = float(gain_db)
        self._gain = 10 ** (self.gain_db / 20)
        # Float64 keeps downmix and gain arithmetic from overflowing. libsoxr
        # may use narrower internals, so its output is checked before encoding.
        self._resampler = (
            soxr.ResampleStream(sample_rate, SAMPLE_RATE, 1, dtype="float64", quality="MQ")
            if sample_rate != SAMPLE_RATE else None
        )
        self._finalized = False
        self.peak_dbfs = -math.inf
        self.rms_dbfs = -math.inf
        self.clipped_samples = 0

    def reset(self) -> None:
        """Discard the previous signal and its meters, keeping format and gain."""
        if self._resampler is not None:
            self._resampler.clear()
        self._finalized = False
        self.peak_dbfs = -math.inf
        self.rms_dbfs = -math.inf
        self.clipped_samples = 0

    def convert(self, block: np.ndarray, *, final: bool = False) -> bytes:
        """Return variable-length mono PCM16LE; final also flushes pending audio.

        Input must have dtype float32 and shape (frames, source channels).
        Empty blocks are valid, including an empty final flush. Finalization
        closes the sequence; call reset before supplying another sequence.
        Input arrays are never modified.
        """
        if (
            type(block) is not np.ndarray
            or block.dtype != np.dtype("float32")
            or block.ndim != 2
            or block.shape[1] != self.channels
            or not np.isfinite(block).all()
        ):
            raise InputError("audio_data", "Capture blocks must contain finite float32 samples with the selected channel count.")
        if type(final) is not bool:
            raise InputError("audio_final", "Capture finalization must be a boolean.")
        if self._finalized:
            raise InputError("audio_finalized", "Reset the converter before starting another capture sequence.")
        if block.shape[0] == 0 and not final:
            return b""

        if self.channels == 2:
            mono = block.mean(axis=1, dtype=np.float64)
        else:
            mono = block[:, 0].astype(np.float64)
        if self._resampler is not None:
            try:
                mono = self._resampler.resample_chunk(mono, last=final)
                delay = self._resampler.delay()
            except (ValueError, RuntimeError):
                self._finalized = True
                raise InputError("audio_resample", "Capture sample-rate conversion failed; reset the converter before reuse.") from None
            if not math.isfinite(delay) or delay > self.MAX_DELAY_SAMPLES:
                self._finalized = True
                raise InputError("audio_delay", "Capture sample-rate conversion exceeded its 50 ms pending-audio limit.")
        self._finalized = final
        if mono.size == 0:
            return b""

        mono *= self._gain
        if not np.isfinite(mono).all():
            self._finalized = True
            raise InputError("audio_data", "Capture sample-rate conversion produced nonfinite samples.")
        self.clipped_samples += int(np.count_nonzero((mono < -1) | (mono > 32767 / 32768)))
        np.clip(mono, -1, 32767 / 32768, out=mono)
        mono *= 32768
        np.rint(mono, out=mono)
        pcm = mono.astype("<i2").tobytes()
        # Meter the exact emitted samples, including integer rounding.
        mono /= 32768
        peak = float(np.max(np.abs(mono)))
        rms = math.sqrt(float(np.dot(mono, mono)) / mono.size)
        self.peak_dbfs = 20 * math.log10(peak) if peak else -math.inf
        self.rms_dbfs = 20 * math.log10(rms) if rms else -math.inf
        return pcm
