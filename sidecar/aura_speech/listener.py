"""The cascade, wired: audio frames in, recognised utterances out.

Each stage runs only because the one before it opened. That ordering is the
whole design — stage 0 costs a fraction of a core and is wrong often, stage 1
costs a millisecond a frame and corrects it, stage 2 costs about two, and
recognition costs hundreds and runs once per utterance. Reversing any two of
them would work and would cost the battery the product is built around.

Nothing here loads a model. Every stage is passed in, so the wiring — which is
what goes wrong — can be tested without any of them.
"""

import numpy as np

from .cascade import EnergyGate, NoiseFloor, Segmenter


class Listener:
    """Feeds frames through the cascade and reports what was said.

    **Without a wake word every sentence in the room is a command.** That is why
    `is_wake` is not optional by accident: when it is absent the listener
    dispatches everything it hears, which is correct for exactly one situation —
    answering a question Aura has just asked, where demanding the wake word again
    would be absurd — and catastrophic for every other.
    """

    def __init__(self, is_speech, recognise, on_utterance,
                 is_wake=None, on_wake=None, is_owner=None, on_rejected=None,
                 tail_seconds: float = 0.5, max_seconds: float = 30.0,
                 arm_seconds: float = 8.0, on_error=None):
        self._is_speech = is_speech
        self._recognise = recognise
        self._on_utterance = on_utterance
        self._is_wake = is_wake
        self._on_wake = on_wake or (lambda at: None)
        self._is_owner = is_owner
        self._on_rejected = on_rejected or (lambda: None)
        self._arm_seconds = arm_seconds
        self._on_error = on_error or (lambda message: None)
        self._gate = EnergyGate(NoiseFloor())
        self._segmenter = Segmenter(tail_seconds=tail_seconds, max_seconds=max_seconds)
        self._armed_until = None

    def feed(self, frame: np.ndarray, at: float) -> None:
        # Stage 0. Always runs, and is the only thing running in a quiet room.
        opened = self._gate.open(frame, at)
        if not opened and not self._segmenter.speaking():
            return

        # Stage 1. Asked only about frames the gate let through: a door closing
        # has energy and is not speech, and this is what makes stage 0 allowed to
        # be that cheap.
        speech = bool(self._is_speech(frame)) if opened else False

        # Stage 2. Asked only about speech, for the same reason again.
        if speech and self._is_wake is not None:
            try:
                if self._is_wake(frame):
                    self._armed_until = at + self._arm_seconds
                    self._on_wake(at)
            except Exception as e:
                self._on_error(f"{type(e).__name__}: {e}")

        utterance = self._segmenter.push(frame, speech, at)
        if utterance is None or len(utterance) == 0:
            return

        if not self._awake(at):
            # Heard, understood to be speech, and deliberately not recognised.
            # Somebody talking in the room did not address this application.
            return

        # Stage 3. Once per utterance, after the wake word rather than before:
        # it costs about fifteen milliseconds, and stage 2 is what makes it rare
        # enough to afford. Verifying every utterance in the room would spend
        # that on every conversation held near the machine.
        if self._is_owner is not None and not self._is_owner(utterance):
            # Refused before recognition, not after: a stranger's words must not
            # travel through a model and into a log on the way to being ignored.
            self._on_rejected()
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

    def _awake(self, at: float) -> bool:
        if self._is_wake is None:
            return True
        if self._armed_until is None or at > self._armed_until:
            return False
        # Spent. Staying armed after one greeting would dispatch whatever
        # conversation happened to follow it.
        self._armed_until = None
        return True
