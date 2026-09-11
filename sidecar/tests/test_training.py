"""Enrolment and wake-word training as functions, not scripts.

Both are driven here with stand-ins for the models: what is being tested is the
part that decides whether there is enough to work with, what is reported while
it runs, and what comes back - none of which needs a 26 MB ONNX file.
"""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.recording import take_path, write_take  # noqa: E402
from aura_speech.training import enrol  # noqa: E402


def a_take(directory, kind, number, level=0.2):
    rng = np.random.default_rng(number)
    write_take(take_path(directory, kind, number),
               (rng.standard_normal(16000) * level).astype(np.float32))


def test_enrolling_with_no_takes_says_so(tmp_path):
    with pytest.raises(ValueError) as raised:
        enrol(tmp_path / "reference", tmp_path / "model.onnx",
              tmp_path / "reference.npy", progress=lambda *a: None,
              embed_factory=lambda path: (lambda audio: np.ones(4, dtype=np.float32)))

    assert "no recordings" in str(raised.value).lower()


def test_enrolling_without_the_model_says_which_file_is_missing(tmp_path):
    a_take(tmp_path / "reference", "reference", 1)

    with pytest.raises(ValueError) as raised:
        enrol(tmp_path / "reference", tmp_path / "absent.onnx",
              tmp_path / "reference.npy", progress=lambda *a: None,
              embed_factory=lambda path: (lambda audio: np.ones(4, dtype=np.float32)))

    assert "absent.onnx" in str(raised.value)


def test_enrolling_writes_the_reference_and_reports_each_take(tmp_path):
    for n in (1, 2, 3):
        a_take(tmp_path / "reference", "reference", n)
    (tmp_path / "model.onnx").write_bytes(b"stand-in")
    seen = []

    result = enrol(tmp_path / "reference", tmp_path / "model.onnx",
                   tmp_path / "reference.npy",
                   progress=lambda stage, done, total, detail: seen.append((stage, done, total)),
                   embed_factory=lambda path: (lambda audio: np.array(
                       [1.0, 0.0, 0.0, 0.0], dtype=np.float32)))

    assert (tmp_path / "reference.npy").is_file()
    assert result["takes"] == 3
    # One similarity per take: the number that says whether they agree.
    assert len(result["similarities"]) == 3
    assert [s for _, s in result["similarities"]] == pytest.approx([1.0, 1.0, 1.0])
    # Progress is reported per take, not once at the end: a person watching a
    # progress bar that only ever shows 0% and then 100% learns nothing from it.
    assert [done for _, done, _ in seen] == [1, 2, 3]
    assert all(total == 3 for _, _, total in seen)


def test_one_take_is_enrolled_but_flagged(tmp_path):
    # Allowed, because refusing would mean a person who recorded once cannot use
    # the product at all; flagged, because one take measures a moment.
    a_take(tmp_path / "reference", "reference", 1)
    (tmp_path / "model.onnx").write_bytes(b"stand-in")

    result = enrol(tmp_path / "reference", tmp_path / "model.onnx",
                   tmp_path / "reference.npy", progress=lambda *a: None,
                   embed_factory=lambda path: (lambda audio: np.array(
                       [0.0, 1.0, 0.0, 0.0], dtype=np.float32)))

    assert result["takes"] == 1
    assert result["warning"]


def test_a_take_that_cannot_be_read_names_the_file(tmp_path):
    a_take(tmp_path / "reference", "reference", 1)
    take_path(tmp_path / "reference", "reference", 2).write_bytes(b"not a wav")
    (tmp_path / "model.onnx").write_bytes(b"stand-in")

    with pytest.raises(ValueError) as raised:
        enrol(tmp_path / "reference", tmp_path / "model.onnx",
              tmp_path / "reference.npy", progress=lambda *a: None,
              embed_factory=lambda path: (lambda audio: np.ones(4, dtype=np.float32)))

    assert "reference-002.wav" in str(raised.value)
