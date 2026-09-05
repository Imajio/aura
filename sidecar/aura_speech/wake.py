"""The wake word as a decision: an embedding in, a probability out.

Deliberately not a neural network of its own. The features come from the speech
embedding model openWakeWord already ships; what is learned here is a single
linear boundary over them, from twenty recordings of one person saying one
phrase. That is the whole personalisation the design asks for, and a logistic
regression is enough to hold it — with the advantage that it trains in a second
on a laptop rather than in a notebook somebody else has to run.

`wake_features` computes that embedding directly through openWakeWord's two
ONNX files rather than through the `openwakeword` package: installing the
package pulls in scipy and scikit-learn — about 150 MB — into a sidecar meant
to sit idle all day. Both the training script and the live detector call this
one function, so a classifier is always scored on exactly the numbers it was
trained on.
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


def wake_features(samples, melspec_session, embedding_session):
    """The 96-number speech embedding both the trainer and the detector use.

    The parameters are openWakeWord's own, read from its source: the
    melspectrogram is scaled by x/10 + 2, and the embedding model consumes
    windows of 76 frames taken every 8. Training and detection must compute
    this identically — a classifier scored on features it was not trained on
    fails silently, by never firing or by firing on everything.

    `samples` is expected at int16 magnitude (roughly -32768..32767) rather
    than the -1..1 range the rest of this codebase captures and stores audio
    in — confirmed by running both scales through this project's own
    `melspectrogram.onnx`: int16 magnitude produces the small positive range
    the x/10 + 2 scaling above is evidently calibrated for (about 1 to 9 on a
    real recording), while -1..1 floats produce mostly negative values well
    outside it. Both callers are responsible for that conversion before
    calling this function.
    """
    audio = np.asarray(samples, dtype=np.float32)
    spec = melspec_session.run(None, {"input": audio[None, :]})[0]
    spec = np.squeeze(spec) / 10.0 + 2.0            # -> [frames, 32]

    windows = [spec[i:i + 76] for i in range(0, max(len(spec) - 76 + 1, 0), 8)]
    if not windows:
        raise ValueError(f"audio too short for one 76-frame window: {len(spec)} frames")

    batch = np.expand_dims(np.array(windows), axis=-1).astype(np.float32)
    embeddings = embedding_session.run(None, {"input_1": batch})[0].reshape(len(windows), -1)
    # The mean over windows, not their concatenation: a clip's window count
    # depends on its length, and a classifier needs the same width from every
    # take. Averaging gives 96 numbers whatever the recording's duration.
    return embeddings.mean(axis=0)
