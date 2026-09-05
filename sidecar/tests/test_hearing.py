"""The lazy loading that sits behind the cascade's stages.

Nothing here opens a device or loads a real model. What is tested is the one
thing in `trained_wake_word` that can go wrong without a microphone: what a
failed load leaves behind for the next frame to find. Both feature sessions are
built on the first speech frame of live listening rather than at startup, so a
transient failure lands there and not in a place anybody is watching.
"""

import pathlib
import sys
import types

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.cascade import FRAME, RATE            # noqa: E402
from aura_speech.hearing import trained_wake_word      # noqa: E402
from aura_speech.wake import WakeClassifier            # noqa: E402


def a_trained_model(path):
    """A real `.npz`, so the failure under test is the session and not the load."""
    WakeClassifier(weights=np.array([1.0], dtype=np.float32), bias=0.0,
                   mean=np.array([0.0], dtype=np.float32),
                   scale=np.array([1.0], dtype=np.float32)).save(path)
    return path


def refusing_onnxruntime(attempts):
    """An onnxruntime whose every `InferenceSession` throws, and counts the tries."""
    def refuse(*args, **kwargs):
        attempts.append(args)
        raise RuntimeError("onnxruntime session refused")

    module = types.ModuleType("onnxruntime")
    module.InferenceSession = refuse
    return module


class TestTrainedWakeWord:

    def test_a_failed_session_leaves_nothing_half_built(self, tmp_path, monkeypatch):
        # The classifier loads before the two sessions are constructed. Publishing
        # it to the shared state as it is built leaves the guard satisfied by a
        # state that has no feature models in it, and the next call then skips the
        # whole init block. Leaving the state empty is what lets the next frame
        # retry — a load that failed once must not brick the wake word.
        attempts = []
        monkeypatch.setitem(sys.modules, "onnxruntime", refusing_onnxruntime(attempts))
        is_wake = trained_wake_word(a_trained_model(tmp_path / "wake-word.npz"))
        frame = np.zeros(FRAME, dtype=np.float32)

        with pytest.raises(RuntimeError):
            is_wake(frame)
        with pytest.raises(RuntimeError):
            is_wake(frame)

        # Tried again rather than skipped: one attempt per call, not one in total.
        assert len(attempts) == 2

    def test_a_failed_session_never_becomes_a_keyerror(self, tmp_path, monkeypatch):
        # The symptom the retry exists to prevent. With a half-built state the init
        # block is skipped from the second call on, the buffer quietly fills, and
        # the frame that completes one second reaches `state["melspec"]` and raises
        # `KeyError: 'melspec'` — an error naming neither the wake word nor the
        # file that was missing, roughly every sixteen speech frames for the rest
        # of the session. The loop is two seconds of frames, which is long enough
        # for a half-built state to reach that boundary twice.
        monkeypatch.setitem(sys.modules, "onnxruntime", refusing_onnxruntime([]))
        is_wake = trained_wake_word(a_trained_model(tmp_path / "wake-word.npz"))
        frame = np.zeros(FRAME, dtype=np.float32)

        for _ in range(2 * RATE // FRAME):
            with pytest.raises(RuntimeError):
                is_wake(frame)
