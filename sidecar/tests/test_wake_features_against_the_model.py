"""Checks `wake_features` against the real ONNX models, when they are present.

The unit tests next door construct `WakeClassifier` directly and never touch a
model at all — they prove the decision boundary and the standardisation are
correct once given a 96-number vector, not that a 96-number vector is what the
real feature pipeline actually produces. `wake_features` is the one function
`train-wake-word.py` and `hearing.py`'s `trained_wake_word` both call, on the
premise that identical code is what keeps a classifier's training and its
scoring from drifting apart — and nothing but this file would notice if that
premise broke: change the melspectrogram's scale, the window size or the step,
and the other 94 tests would keep passing while the wake word quietly stopped
recognising anything.

Skipped, not failed, when the two ONNX files are not on this machine: they are
openWakeWord's own release assets, placed under `models/` by hand rather than
by pip, and `models/` is git-ignored, so their absence is the normal case
everywhere except the machine the project is developed on.
"""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.wake import wake_features  # noqa: E402

MELSPEC = (pathlib.Path(__file__).resolve().parents[2]
          / "models" / "openwakeword" / "melspectrogram.onnx")
EMBEDDING = (pathlib.Path(__file__).resolve().parents[2]
            / "models" / "openwakeword" / "embedding_model.onnx")

pytestmark = pytest.mark.skipif(
    not (MELSPEC.is_file() and EMBEDDING.is_file()),
    reason=f"no openWakeWord feature models at {MELSPEC.parent}")

# Separate ONNX sessions measured 3.24e-05 apart on identical input during
# review — float32 noise between sessions, not a scale or shape error. An
# exact comparison here would be flaky rather than useful.
TOLERANCE = 1e-3


def _noise(n: int) -> np.ndarray:
    """A deterministic synthetic clip at the int16 magnitude `wake_features` expects.

    A fresh `default_rng(0)` every call, not a shared generator: two calls with
    the same `n` must produce the same clip regardless of what ran before them,
    which is what the determinism test below actually needs to be testing.
    """
    return (np.random.default_rng(0).uniform(-1.0, 1.0, n) * 32767.0).astype(np.float32)


@pytest.fixture(scope="module")
def sessions():
    ort = pytest.importorskip("onnxruntime")
    melspec = ort.InferenceSession(str(MELSPEC), providers=["CPUExecutionProvider"])
    embedding = ort.InferenceSession(str(EMBEDDING), providers=["CPUExecutionProvider"])
    return melspec, embedding


def test_it_produces_a_96_number_float32_vector(sessions):
    melspec, embedding = sessions
    features = wake_features(_noise(16000), melspec, embedding)

    assert features.shape == (96,)
    assert features.dtype == np.float32


def test_two_different_durations_both_give_96(sessions):
    # The fixed-width property the original embed_all broke: it concatenated
    # windows instead of averaging them, so a longer clip produced a wider
    # vector and the regression could not be fitted across takes of different
    # lengths. One second and three seconds are clearly different window counts.
    melspec, embedding = sessions
    short = wake_features(_noise(16000), melspec, embedding)
    long = wake_features(_noise(48000), melspec, embedding)

    assert short.shape == (96,)
    assert long.shape == (96,)


def test_it_is_deterministic(sessions):
    melspec, embedding = sessions
    samples = _noise(16000)

    first = wake_features(samples, melspec, embedding)
    second = wake_features(samples, melspec, embedding)

    assert np.allclose(first, second, atol=TOLERANCE)


def test_it_matches_recorded_constants(sessions):
    # Computed once against the real models on a fixed seed, then pasted in.
    # These five numbers encode the melspectrogram's x/10 + 2 scaling, the
    # 76-frame window and the step of 8 all at once: change any of those three
    # and this fails — which is the entire point of the test, since nothing
    # else here would notice.
    melspec, embedding = sessions
    features = wake_features(_noise(16000), melspec, embedding)

    recorded = np.array([-2.235705, 18.430822, 1.2807717, -1.1059049, 15.353528],
                        dtype=np.float32)
    assert np.allclose(features[:5], recorded, atol=TOLERANCE)
