"""The wake word, as a decision rather than as a model.

The training script needs recordings and cannot run here. What is testable is
the thing that runs a thousand times a minute: the classifier that turns an
embedding into yes or no, and its refusal to answer at all when there is nothing
trained.
"""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.wake import WakeClassifier  # noqa: E402


def test_it_fires_on_what_it_was_trained_on():
    classifier = WakeClassifier(weights=np.array([1.0, 0.0], dtype=np.float32),
                                bias=-0.5,
                                mean=np.zeros(2, dtype=np.float32),
                                scale=np.ones(2, dtype=np.float32))

    assert classifier.score(np.array([10.0, 0.0], dtype=np.float32)) > 0.5


def test_it_stays_quiet_otherwise():
    classifier = WakeClassifier(weights=np.array([1.0, 0.0], dtype=np.float32),
                                bias=-0.5,
                                mean=np.zeros(2, dtype=np.float32),
                                scale=np.ones(2, dtype=np.float32))

    assert classifier.score(np.array([-10.0, 0.0], dtype=np.float32)) < 0.5


def test_features_are_standardised_before_scoring():
    # The embedding dimensions have wildly different ranges; without the
    # standardisation recorded at training time, one of them decides everything.
    classifier = WakeClassifier(weights=np.array([1.0], dtype=np.float32),
                                bias=0.0,
                                mean=np.array([100.0], dtype=np.float32),
                                scale=np.array([10.0], dtype=np.float32))

    assert classifier.score(np.array([100.0], dtype=np.float32)) == pytest.approx(0.5)


def test_it_round_trips_through_disk(tmp_path):
    classifier = WakeClassifier(weights=np.array([1.0, -2.0], dtype=np.float32),
                                bias=0.25,
                                mean=np.array([0.0, 1.0], dtype=np.float32),
                                scale=np.array([1.0, 2.0], dtype=np.float32))
    path = tmp_path / "wake-word.npz"
    classifier.save(path)

    loaded = WakeClassifier.load(path)

    assert np.allclose(loaded.weights, classifier.weights)
    assert loaded.bias == pytest.approx(classifier.bias)
    assert np.allclose(loaded.scale, classifier.scale)


def test_a_zero_scale_does_not_divide_by_zero(tmp_path):
    # A feature that never varied across the training takes has zero spread.
    # Dividing by it produces infinities that score as a permanent wake.
    classifier = WakeClassifier(weights=np.array([1.0], dtype=np.float32),
                                bias=0.0,
                                mean=np.array([0.0], dtype=np.float32),
                                scale=np.array([0.0], dtype=np.float32))

    score = classifier.score(np.array([5.0], dtype=np.float32))

    assert np.isfinite(score)
