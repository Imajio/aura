"""Recording takes: the numbering, the file, and the level.

Capture itself is not tested here — it needs a microphone, and the project's
rule is that a test suite never opens one. What is tested is everything that
decides where a recording lands, because that is what can destroy one.
"""

import pathlib
import sys
import wave

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.recording import (  # noqa: E402
    loudness, next_take_number, take_path, write_take)


def test_the_first_take_in_an_empty_directory_is_one(tmp_path):
    assert next_take_number(tmp_path, "reference") == 1


def test_recording_again_continues_past_what_is_there(tmp_path):
    # The defect this exists for: numbering from one again overwrites recordings
    # that only re-speaking can restore.
    for n in (1, 2, 3):
        write_take(take_path(tmp_path, "reference", n), np.zeros(16, dtype=np.float32))

    assert next_take_number(tmp_path, "reference") == 4


def test_a_gap_does_not_reuse_a_number(tmp_path):
    # Somebody deleted take 2 because it was too quiet. Refilling the hole would
    # silently pair a new recording with an old name in anyone's notes.
    for n in (1, 3):
        write_take(take_path(tmp_path, "reference", n), np.zeros(16, dtype=np.float32))

    assert next_take_number(tmp_path, "reference") == 4


def test_takes_of_another_kind_do_not_count(tmp_path):
    write_take(take_path(tmp_path, "wake", 7), np.zeros(16, dtype=np.float32))

    assert next_take_number(tmp_path, "reference") == 1


def test_a_written_take_is_a_wav_at_16k_mono(tmp_path):
    path = take_path(tmp_path, "reference", 1)

    write_take(path, np.linspace(-1.0, 1.0, 800, dtype=np.float32))

    with wave.open(str(path), "rb") as f:
        assert f.getnchannels() == 1
        assert f.getsampwidth() == 2
        assert f.getframerate() == 16000
        assert f.getnframes() == 800


def test_samples_are_clipped_rather_than_wrapped(tmp_path):
    # A sample above 1.0 that wraps becomes a full-scale click in the other
    # direction, which is worse than the clipping it came from.
    path = take_path(tmp_path, "reference", 1)

    write_take(path, np.array([2.0, -2.0], dtype=np.float32))

    with wave.open(str(path), "rb") as f:
        pcm = np.frombuffer(f.readframes(2), dtype="<i2")
    assert pcm.tolist() == [32767, -32767]


def test_loudness_of_silence_is_zero_and_of_a_tone_is_not():
    assert loudness(np.zeros(1000, dtype=np.float32)) == 0.0
    assert loudness(np.full(1000, 0.5, dtype=np.float32)) == pytest.approx(0.5, abs=1e-6)
