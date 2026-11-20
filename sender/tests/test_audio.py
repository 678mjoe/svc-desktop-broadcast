import math

import numpy as np
import pytest

from svc_broadcast_sender.audio import PcmConverter
from svc_broadcast_sender.errors import InputError


def _samples(pcm):
    return np.frombuffer(pcm, dtype="<i2")


def _tone(rate, frequency=1000, amplitude=0.25):
    time = np.arange(rate, dtype=np.float64) / rate
    return (amplitude * np.sin(2 * np.pi * frequency * time)).astype(np.float32)[:, None]


@pytest.mark.parametrize("rate", [0, -44100, 22050, 96000, 44100.0, True, math.nan, math.inf, "44100", None])
def test_invalid_rates_are_input_errors(rate):
    with pytest.raises(InputError):
        PcmConverter(rate, 1)


@pytest.mark.parametrize("channels", [0, -1, 3, 1.0, True, math.nan, "2", None])
def test_invalid_channels_are_input_errors(channels):
    with pytest.raises(InputError):
        PcmConverter(48000, channels)


@pytest.mark.parametrize("gain", [-60.01, 12.01, math.nan, math.inf, -math.inf, "secret", None, True, 10 ** 1000])
def test_invalid_gain_is_a_safe_input_error(gain):
    with pytest.raises(InputError) as error:
        PcmConverter(48000, 1, gain)
    assert "secret" not in str(error.value)


@pytest.mark.parametrize("block", [
    np.array([[math.nan]], dtype=np.float32),
    np.array([[math.inf]], dtype=np.float32),
    np.array([[-math.inf]], dtype=np.float32),
    np.zeros((2, 1), dtype=np.float64),
    np.zeros((2, 1), dtype=np.int16),
    np.zeros((2, 2), dtype=np.float32),
    np.zeros((2,), dtype=np.float32),
    np.zeros((2, 1, 1), dtype=np.float32),
    np.zeros((), dtype=np.float32),
    [[0.0]],
    None,
])
def test_invalid_blocks_are_rejected_without_poisoning_stream(block):
    converter = PcmConverter(44100, 1)
    prefix = np.full((173, 1), 0.125, dtype=np.float32)
    first = converter.convert(prefix)
    with pytest.raises(InputError):
        converter.convert(block)
    suffix = np.full((727, 1), 0.25, dtype=np.float32)
    result = first + converter.convert(suffix, final=True)
    expected = PcmConverter(44100, 1).convert(np.concatenate((prefix, suffix)), final=True)
    assert result == expected


def test_pcm_limits_rounding_and_little_endian_bytes():
    converter = PcmConverter(48000, 1)
    block = np.array([0, -1, 1, 1.5, -1.5, 0.5, -0.5, 32767 / 32768, 1 / 32768], dtype=np.float32)[:, None]
    result = converter.convert(block)
    assert result == b"\x00\x00\x00\x80\xff\x7f\xff\x7f\x00\x80\x00\x40\x00\xc0\xff\x7f\x01\x00"
    assert converter.clipped_samples == 3
    assert converter.peak_dbfs == 0
    normalized = _samples(result).astype(np.float64) / 32768
    assert converter.rms_dbfs == pytest.approx(20 * np.log10(np.sqrt(np.mean(normalized ** 2))))


@pytest.mark.parametrize("rate", [44100, 48000])
def test_opposite_stereo_channels_cancel(rate):
    left = _tone(rate)[:, 0]
    block = np.column_stack((left, -left))
    converter = PcmConverter(rate, 2)
    result = _samples(converter.convert(block, final=True))
    assert len(result) == 48000
    assert not np.any(result)
    assert converter.peak_dbfs == -math.inf
    assert converter.rms_dbfs == -math.inf
    assert converter.clipped_samples == 0
    assert converter.channels == 2


@pytest.mark.parametrize("rate", [44100, 48000])
def test_left_only_stereo_is_half_amplitude(rate):
    left = _tone(rate)[:, 0]
    block = np.column_stack((left, np.zeros_like(left)))
    result = _samples(PcmConverter(rate, 2).convert(block, final=True)).astype(np.float64) / 32768
    interior = result[1000:-1000]
    assert np.max(np.abs(interior)) == pytest.approx(0.125, abs=5e-5)
    assert np.sqrt(np.mean(interior ** 2)) == pytest.approx(0.125 / np.sqrt(2), abs=1e-4)


@pytest.mark.parametrize("rate", [44100, 48000])
@pytest.mark.parametrize("channels", [1, 2])
def test_streaming_is_invariant_to_chunk_boundaries(rate, channels):
    rng = np.random.default_rng(247)
    block = rng.uniform(-0.3, 0.3, (rate + 137, channels)).astype(np.float32)
    expected = PcmConverter(rate, channels, 3).convert(block, final=True)
    converter = PcmConverter(rate, channels, 3)
    chunks = []
    offset = 0
    for size in (1, 17, 441, 960, 7, 2048, 100, 3333):
        chunks.append(converter.convert(block[offset:offset + size]))
        chunks.append(converter.convert(np.empty((0, channels), dtype=np.float32)))
        offset += size
    while offset < len(block):
        chunks.append(converter.convert(block[offset:offset + 211]))
        offset += 211
    chunks.append(converter.convert(np.empty((0, channels), dtype=np.float32), final=True))
    assert b"".join(chunks) == expected


def test_resampling_preserves_duration_frequency_and_bounds_pending_audio():
    block = _tone(44100)
    converter = PcmConverter(44100, 1)
    chunks = []
    emitted = 0
    for stop in range(441, len(block) + 1, 441):
        chunk = converter.convert(block[stop - 441:stop])
        chunks.append(chunk)
        emitted += len(chunk) // 2
        assert 0 <= stop * 48000 / 44100 - emitted <= 2400
    assert emitted > 0
    chunks.append(converter.convert(np.empty((0, 1), dtype=np.float32), final=True))
    result = _samples(b"".join(chunks)).astype(np.float64) / 32768
    assert len(result) == 48000
    spectrum = np.abs(np.fft.rfft(result))
    frequencies = np.fft.rfftfreq(len(result), d=1 / 48000)
    assert frequencies[np.argmax(spectrum)] == pytest.approx(1000, abs=1)
    assert np.sqrt(np.mean(result[1000:-1000] ** 2)) == pytest.approx(0.25 / np.sqrt(2), abs=1e-4)


def test_gain_saturates_and_clipping_count_accumulates():
    converter = PcmConverter(48000, 1, 12)
    block = np.array([0.5, -0.5, 0, 0.125], dtype=np.float32)[:, None]
    result = _samples(converter.convert(block))
    np.testing.assert_array_equal(result[:3], [32767, -32768, 0])
    assert result[3] == round(0.125 * 10 ** (12 / 20) * 32768)
    assert converter.clipped_samples == 2
    converter.convert(block)
    assert converter.clipped_samples == 4
    assert converter.peak_dbfs == 0


def test_attenuation_and_meters_follow_emitted_pcm():
    converter = PcmConverter(48000, 1, -60)
    result = _samples(converter.convert(np.ones((20, 1), dtype=np.float32)))
    np.testing.assert_array_equal(result, np.full(20, 33, dtype=np.int16))
    assert converter.peak_dbfs == pytest.approx(20 * math.log10(33 / 32768))
    assert converter.rms_dbfs == pytest.approx(converter.peak_dbfs)
    assert converter.clipped_samples == 0
    converter.convert(np.zeros((10, 1), dtype=np.float32))
    assert converter.peak_dbfs == converter.rms_dbfs == -math.inf


@pytest.mark.parametrize("rate", [44100, 48000])
def test_reset_discards_pending_audio_and_clears_metrics(rate):
    converter = PcmConverter(rate, 1, 12)
    converter.convert(np.ones((3000, 1), dtype=np.float32))
    assert converter.clipped_samples > 0
    converter.convert(np.full((13, 1), 0.25, dtype=np.float32))
    converter.reset()
    assert converter.peak_dbfs == converter.rms_dbfs == -math.inf
    assert converter.clipped_samples == 0
    silence = np.zeros((1777, 1), dtype=np.float32)
    result = converter.convert(silence, final=True)
    assert result == PcmConverter(rate, 1, 12).convert(silence, final=True)
    assert not np.any(_samples(result))
    converter.reset()
    signal = _tone(rate)[:3000]
    assert converter.convert(signal, final=True) == PcmConverter(rate, 1, 12).convert(signal, final=True)


@pytest.mark.parametrize("rate", [44100, 48000])
def test_empty_input_finalization_and_meter_retention(rate):
    converter = PcmConverter(rate, 1)
    empty = np.empty((0, 1), dtype=np.float32)
    assert converter.convert(empty) == b""
    assert converter.peak_dbfs == converter.rms_dbfs == -math.inf
    assert converter.convert(empty, final=True) == b""
    with pytest.raises(InputError):
        converter.convert(empty, final=True)
    converter.reset()
    converter.convert(np.full((3000, 1), 0.25, dtype=np.float32))
    meters = (converter.peak_dbfs, converter.rms_dbfs, converter.clipped_samples)
    assert converter.convert(empty) == b""
    assert (converter.peak_dbfs, converter.rms_dbfs, converter.clipped_samples) == meters
    with pytest.raises(InputError):
        converter.convert(empty, final="secret")


@pytest.mark.parametrize("rate", [44100, 48000])
def test_noncontiguous_input_is_not_modified(rate):
    original = np.arange(6000, dtype=np.float32).reshape(3000, 2) / 12000
    block = original[::2, ::-1]
    before = original.copy()
    result = PcmConverter(rate, 2, 6).convert(block, final=True)
    expected = PcmConverter(rate, 2, 6).convert(block.copy(), final=True)
    assert result == expected
    np.testing.assert_array_equal(original, before)


@pytest.mark.parametrize("rate", [44100, 48000])
def test_extreme_finite_samples_never_emit_wrapped_pcm_and_can_recover(rate):
    maximum = np.finfo(np.float32).max
    opposite = np.tile(np.array([maximum, -maximum], dtype=np.float32), (3000, 1))
    converter = PcmConverter(rate, 2, 12)
    assert not np.any(_samples(converter.convert(opposite, final=True)))
    converter.reset()
    positive = np.full((3000, 2), maximum, dtype=np.float32)
    try:
        result = _samples(converter.convert(positive, final=True))
    except InputError as error:
        # Native resamplers may overflow internally even with float64 I/O.
        # The caller must receive a safe failure, never corrupt PCM.
        assert error.code == "audio_data"
        with pytest.raises(InputError):
            converter.convert(np.zeros((960, 2), dtype=np.float32))
        converter.reset()
        recovered = converter.convert(np.zeros((3000, 2), dtype=np.float32), final=True)
        assert not np.any(_samples(recovered))
    else:
        assert np.all(result == 32767)
        assert converter.clipped_samples == len(result)
