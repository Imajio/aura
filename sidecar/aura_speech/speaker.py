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
        # Normalised here rather than at each call site: `similarity` is a dot
        # product that is only a cosine similarity while this holds, and the
        # threshold is calibrated for a cosine. A reference read back from a
        # file with any other norm silently stops comparing what it claims to.
        vector = np.asarray(embedding, dtype=np.float32)
        norm = float(np.linalg.norm(vector))
        if norm <= 0.0:
            raise ValueError("a speaker reference cannot be the zero vector")
        self.embedding = (vector / norm).astype(np.float32)

    @classmethod
    def from_embeddings(cls, embeddings) -> "Enrolment":
        embeddings = list(embeddings)
        if not embeddings:
            # An empty reference matches everybody at zero, which reads as "no
            # voice is the owner" or, with an unlucky threshold, as "everybody
            # is". Refusing is the only honest answer.
            raise ValueError("cannot enrol on no recordings")
        mean = np.mean(np.stack(embeddings), axis=0)
        return cls(mean.astype(np.float32))

    def similarity(self, embedding: np.ndarray) -> float:
        """The cosine between the reference and one probe.

        The probe is normalised here rather than trusted to arrive that way. The
        reference already is, so the dot product alone would be a cosine only
        while every caller happened to hand over a unit vector — and the
        threshold is calibrated for a cosine. `embed()` does return unit vectors,
        but `verifier` takes `embed` as an injected callable, which makes that
        caller discipline on a public seam: a probe of norm 3 at a true cosine of
        0.30 scores 0.90 and a stranger is admitted.
        """
        probe = np.asarray(embedding, dtype=np.float32)
        norm = float(np.linalg.norm(probe))
        if norm <= 0.0:
            # No direction to compare against. Zero refuses at any positive
            # threshold, which is the fail-closed answer, and dividing anyway
            # would give a NaN. Not an exception: `enrol-speaker.py` calls this
            # for its per-take report, where a silent take should read as a bad
            # number rather than end the run.
            return 0.0
        return float(np.dot(self.embedding, probe) / norm)

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
        try:
            if "enrolment" not in state:
                path = pathlib.Path(reference_path)
                state["enrolment"] = Enrolment.load(path) if path.is_file() else None
            enrolment = state["enrolment"]
            return enrolment is not None and enrolment.matches(embed(audio), threshold)
        except Exception:
            return False

    return is_owner
