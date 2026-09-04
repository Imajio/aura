"""The cascade, wired: audio frames in, recognised utterances out.

Each stage runs only because the one before it opened. That ordering is the
whole design — stage 0 costs a fraction of a core and is wrong often, stage 1
costs a millisecond a frame and corrects it, and recognition costs hundreds of
milliseconds and runs once per utterance. Reversing any two of them would work
and would cost the battery the product is built around.

Nothing here loads a model. The detector and the recogniser are passed in, so
the wiring — which is what goes wrong — can be tested without either.
"""

import numpy as np

from .cascade import EnergyGate, NoiseFloor, Segmenter


class Listener:
    """Feeds frames through the cascade and reports what was said."""

    def __init__(self, is_speech, recognise, on_utterance,
                 tail_seconds: float = 0.5, max_seconds: float = 30.0,
                 on_error=None):
        self._is_speech = is_speech
        self._recognise = recognise
        self._on_utterance = on_utterance
        self._on_error = on_error or (lambda message: None)
        self._gate = EnergyGate(NoiseFloor())
        self._segmenter = Segmenter(tail_seconds=tail_seconds, max_seconds=max_seconds)

    def feed(self, frame: np.ndarray, at: float) -> None:
        # Stage 0. Always runs, and is the only thing running in a quiet room.
        opened = self._gate.open(frame, at)
        if not opened and not self._segmenter.speaking():
            return

        # Stage 1. Asked only about frames the gate let through: a door closing
        # has energy and is not speech, and this is what makes stage 0 allowed to
        # be wrong so cheaply.
        speech = bool(self._is_speech(frame)) if opened else False

        utterance = self._segmenter.push(frame, speech, at)
        if utterance is None or len(utterance) == 0:
            return

        try:
            text = self._recognise(utterance)
        except Exception as e:
            # A model that failed once must not leave the application deaf for
            # the rest of the session.
            self._on_error(f"{type(e).__name__}: {e}")
            return

        if text and text.strip():
            self._on_utterance(text.strip())
        # An empty transcript is a cough, not a command. Passing it on would
        # dispatch an empty task to an agent.
