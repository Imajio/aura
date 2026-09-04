"""The features the speaker model eats. Pure arithmetic, so it is worth testing
exactly rather than by eye."""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.features import fbank  # noqa: E402

RATE = 16000


def tone(hz, seconds=1.0, rate=RATE):
    t = np.arange(int(seconds * rate)) / rate
    return np.sin(2 * np.pi * hz * t).astype(np.float32)


def test_the_shape_is_frames_by_mel_bins():
    feats = fbank(tone(440, seconds=1.0))

    assert feats.ndim == 2
    assert feats.shape[1] == 80
    # 25 ms windows every 10 ms over one second.
    assert 95 <= feats.shape[0] <= 100


def test_a_low_tone_lights_low_bins_and_a_high_tone_high_ones():
    # A tone that is absent for the first half and present for the second half
    # has an envelope that varies over time, so mean-normalisation cannot erase it.
    seconds = 1.0
    silence_samples = int(seconds * RATE / 2)
    silence = np.zeros(silence_samples, dtype=np.float32)
    low_tone = tone(200, seconds=seconds / 2)
    high_tone = tone(4000, seconds=seconds / 2)

    low = fbank(np.concatenate([silence, low_tone]))
    high = fbank(np.concatenate([silence, high_tone]))

    # Take only the second-half frames where the tone is actually present.
    low_tone_frames = low[len(low) // 2:]
    high_tone_frames = high[len(high) // 2:]

    assert int(np.argmax(low_tone_frames.mean(axis=0))) < int(
        np.argmax(high_tone_frames.mean(axis=0))
    )


def test_it_is_mean_normalised_over_time():
    # Cepstral mean normalisation: what survives is the shape of the voice, not
    # the gain of the microphone that recorded it.
    feats = fbank(tone(440))

    assert np.allclose(feats.mean(axis=0), 0.0, atol=1e-5)


def test_loudness_does_not_change_the_features():
    quiet = fbank(tone(440) * 0.05)
    loud = fbank(tone(440) * 0.8)

    assert np.allclose(quiet, loud, atol=1e-3)


def test_audio_shorter_than_one_window_is_refused_rather_than_padded():
    # Silently padding produces features of silence, and a speaker check would
    # then confidently compare somebody to nothing.
    with pytest.raises(ValueError):
        fbank(np.zeros(100, dtype=np.float32))


def test_the_output_is_float32():
    # The ONNX model declares float inputs and rejects float64 at run time.
    assert fbank(tone(440)).dtype == np.float32
