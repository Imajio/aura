"""Telling the owner from everybody else.

The design makes this mandatory and not switchable off, which decides how every
failure here resolves: **not knowing means no.** A missing reference, a model
that will not load, a recording too short to have features — each of them
refuses. An application that accepts a stranger because something went wrong is
worse than one that accepts nobody and says why.
"""

import pathlib

import numpy as np

from .features import fbank

DEFAULT_THRESHOLD = 0.5


def embed(samples: np.ndarray, session) -> np.ndarray:
    """One L2-normalised speaker vector for this audio."""
    feats = fbank(samples)[None, :, :]
    vector = session.run(None, {"feats": feats})[0][0]
    return (vector / np.linalg.norm(vector)).astype(np.float32)


class Enrolment:
    """What the owner sounds like: the average of several takes, normalised."""

    def __init__(self, embedding: np.ndarray):
        self.embedding = embedding

    @classmethod
    def from_embeddings(cls, embeddings) -> "Enrolment":
        embeddings = list(embeddings)
        if not embeddings:
            # An empty reference matches everybody at zero, which reads as "no
            # voice is the owner" or, with an unlucky threshold, as "everybody
            # is". Refusing is the only honest answer.
            raise ValueError("cannot enrol on no recordings")
        mean = np.mean(np.stack(embeddings), axis=0)
        return cls((mean / np.linalg.norm(mean)).astype(np.float32))

    def similarity(self, embedding: np.ndarray) -> float:
        return float(np.dot(self.embedding, embedding))

    def matches(self, embedding: np.ndarray, threshold: float = DEFAULT_THRESHOLD) -> bool:
        return self.similarity(embedding) >= threshold

    def save(self, path) -> None:
        path = pathlib.Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        np.save(path, self.embedding)

    @classmethod
    def load(cls, path) -> "Enrolment":
        return cls(np.load(pathlib.Path(path)).astype(np.float32))


def verifier(reference_path, embed, threshold: float = DEFAULT_THRESHOLD):
    """Returns `is_owner(audio) -> bool`, loading the reference on the first call.

    Fails closed, always. Verification is mandatory by design, so every way this
    can go wrong resolves to a refusal rather than to an admission.
    """
    state = {}

    def is_owner(audio: np.ndarray) -> bool:
        if "enrolment" not in state:
            path = pathlib.Path(reference_path)
            state["enrolment"] = Enrolment.load(path) if path.is_file() else None
        enrolment = state["enrolment"]
        if enrolment is None:
            return False
        try:
            return enrolment.matches(embed(audio), threshold)
        except Exception:
            return False

    return is_owner
