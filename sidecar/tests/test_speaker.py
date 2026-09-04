"""Telling the owner from everybody else.

The model is injected as a plain callable, so the arithmetic around it — the
averaging, the threshold, the refusal to compare against nothing — is tested
without loading ninety megabytes of ONNX.
"""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.speaker import Enrolment, verifier  # noqa: E402


def unit(*values):
    vector = np.array(values, dtype=np.float32)
    return vector / np.linalg.norm(vector)


class TestEnrolment:

    def test_a_reference_is_the_average_of_its_takes(self):
        enrolment = Enrolment.from_embeddings([unit(1, 0), unit(0, 1)])

        assert enrolment.embedding.shape == (2,)
        assert np.isclose(np.linalg.norm(enrolment.embedding), 1.0)
        # The average of (1, 0) and (0, 1) is (0.5, 0.5), which normalises to (1, 1) / sqrt(2)
        expected = unit(1, 1)
        assert np.allclose(enrolment.embedding, expected)

    def test_the_same_voice_matches(self):
        enrolment = Enrolment.from_embeddings([unit(1, 0)])

        assert enrolment.similarity(unit(1, 0)) == pytest.approx(1.0)
        assert enrolment.matches(unit(1, 0), threshold=0.5)

    def test_a_different_voice_does_not(self):
        enrolment = Enrolment.from_embeddings([unit(1, 0)])

        assert enrolment.similarity(unit(0, 1)) == pytest.approx(0.0, abs=1e-6)
        assert not enrolment.matches(unit(0, 1), threshold=0.5)

    def test_it_survives_a_round_trip_through_disk(self, tmp_path):
        enrolment = Enrolment.from_embeddings([unit(1, 2, 3)])
        path = tmp_path / "reference.npy"
        enrolment.save(path)

        assert np.allclose(Enrolment.load(path).embedding, enrolment.embedding)

    def test_enrolling_on_nothing_is_refused(self):
        # An empty reference matches everybody at zero similarity, which reads as
        # "no voice is the owner" — or, with an unlucky threshold, as "everybody
        # is". Refusing is the only honest answer.
        with pytest.raises(ValueError):
            Enrolment.from_embeddings([])

    def test_a_denormalized_reference_is_normalized_on_load(self, tmp_path):
        # A reference saved with a norm other than 1.0 must be normalised on
        # load so that similarity remains a cosine similarity. Without this,
        # a vector with norm 3 could accept strangers silently by confusing the
        # threshold calibration.
        path = tmp_path / "reference.npy"
        denorm = np.array([3.0, 0.0, 0.0], dtype=np.float32)
        np.save(path, denorm)

        enrolment = Enrolment.load(path)
        # The loaded vector should be normalised: norm 1.0
        assert np.isclose(np.linalg.norm(enrolment.embedding), 1.0)
        # The normalized direction must still point to (1, 0, 0)
        assert np.allclose(enrolment.embedding, unit(1, 0, 0))
        # A perpendicular voice must not match at any reasonable threshold
        assert enrolment.similarity(unit(0, 1, 0)) == pytest.approx(0.0, abs=1e-6)
        assert not enrolment.matches(unit(0, 1, 0), threshold=0.5)


class TestVerifier:

    def test_it_accepts_the_enrolled_voice(self, tmp_path):
        path = tmp_path / "reference.npy"
        Enrolment.from_embeddings([unit(1, 0)]).save(path)

        verify = verifier(path, embed=lambda audio: unit(1, 0), threshold=0.5)

        assert verify(np.zeros(16000, dtype=np.float32))

    def test_it_refuses_a_stranger(self, tmp_path):
        path = tmp_path / "reference.npy"
        Enrolment.from_embeddings([unit(1, 0)]).save(path)

        verify = verifier(path, embed=lambda audio: unit(0, 1), threshold=0.5)

        assert not verify(np.zeros(16000, dtype=np.float32))

    def test_without_a_reference_nothing_is_accepted(self, tmp_path):
        # Speaker verification is mandatory by design and cannot be switched off.
        # A missing reference must therefore fail closed: an application that
        # accepts everybody because it was never enrolled is worse than one that
        # accepts nobody and says so.
        verify = verifier(tmp_path / "absent.npy", embed=lambda audio: unit(1, 0),
                          threshold=0.5)

        assert not verify(np.zeros(16000, dtype=np.float32))

    def test_a_model_that_throws_refuses_rather_than_admits(self, tmp_path):
        path = tmp_path / "reference.npy"
        Enrolment.from_embeddings([unit(1, 0)]).save(path)

        def explode(audio):
            raise RuntimeError("model went away")

        verify = verifier(path, embed=explode, threshold=0.5)

        assert not verify(np.zeros(16000, dtype=np.float32))

    def test_a_corrupted_reference_refuses_consistently(self, tmp_path):
        # A reference file that exists but will not parse (corrupted content,
        # truncated write) must refuse rather than raise. Before the fix,
        # the exception prevented state["enrolment"] from being set, so it
        # raised on every call instead of settling into cached refusal.
        path = tmp_path / "reference.npy"
        # Write garbage that will fail to load
        path.write_bytes(b"not a valid numpy file")

        verify = verifier(path, embed=lambda audio: unit(1, 0), threshold=0.5)

        # First call should refuse (and not raise)
        assert not verify(np.zeros(16000, dtype=np.float32))
        # Second call should also refuse, not raise again (proves caching works)
        assert not verify(np.zeros(16000, dtype=np.float32))
