"""The wake word as a decision: an embedding in, a probability out.

Deliberately not a neural network of its own. The features come from the speech
embedding model openWakeWord already ships; what is learned here is a single
linear boundary over them, from twenty recordings of one person saying one
phrase. That is the whole personalisation the design asks for, and a logistic
regression is enough to hold it — with the advantage that it trains in a second
on a laptop rather than in a notebook somebody else has to run.
"""

import pathlib

import numpy as np


class WakeClassifier:
    """A linear boundary over speech embeddings, with its training statistics."""

    def __init__(self, weights: np.ndarray, bias: float,
                 mean: np.ndarray, scale: np.ndarray):
        self.weights = np.asarray(weights, dtype=np.float32)
        self.bias = float(bias)
        self.mean = np.asarray(mean, dtype=np.float32)
        # A feature that never varied across the training takes has zero spread;
        # dividing by it yields infinities that score as a permanent wake.
        self.scale = np.where(np.asarray(scale, dtype=np.float32) > 1e-6,
                              scale, 1.0).astype(np.float32)

    def score(self, features: np.ndarray) -> float:
        standardised = (np.asarray(features, dtype=np.float32) - self.mean) / self.scale
        logit = float(np.dot(standardised, self.weights) + self.bias)
        return float(1.0 / (1.0 + np.exp(-logit)))

    def save(self, path) -> None:
        path = pathlib.Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        np.savez(path, weights=self.weights, bias=self.bias,
                 mean=self.mean, scale=self.scale)

    @classmethod
    def load(cls, path) -> "WakeClassifier":
        data = np.load(pathlib.Path(path))
        return cls(data["weights"], float(data["bias"]), data["mean"], data["scale"])
