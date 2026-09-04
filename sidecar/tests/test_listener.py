"""The cascade wired together: frames in, utterances out.

Every stage is injected. The point of these tests is the wiring — which stage
runs when, and what happens when one of them fails — and none of that needs a
model or a microphone to be wrong.
"""

import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.cascade import FRAME, FRAME_SECONDS  # noqa: E402
from aura_speech.listener import Listener  # noqa: E402


def frames(count, level):
    rng = np.random.default_rng(0)
    return [(rng.standard_normal(FRAME) * level).astype(np.float32) for _ in range(count)]


def listener(**kwargs):
    calls = {"vad": 0, "recognise": 0}
    heard = []

    def vad(frame):
        calls["vad"] += 1
        return kwargs.get("is_speech", True)

    def recognise(audio):
        calls["recognise"] += 1
        return kwargs.get("text", "почини тесты")

    return Listener(
        is_speech=kwargs.get("vad", vad),
        recognise=kwargs.get("recogniser", recognise),
        on_utterance=heard.append,
        tail_seconds=0.5,
    ), calls, heard


def feed(listener_, audio_frames, start=0.0):
    at = start
    for frame in audio_frames:
        listener_.feed(frame, at)
        at += FRAME_SECONDS
    return at


def test_a_quiet_room_never_reaches_the_vad():
    # The measurable requirement: no inference at all when nobody is talking.
    ear, calls, heard = listener()

    feed(ear, frames(300, 0.001))

    assert calls["vad"] == 0
    assert calls["recognise"] == 0
    assert heard == []


def test_speech_runs_the_vad_and_produces_an_utterance():
    ear, calls, heard = listener()

    at = feed(ear, frames(200, 0.001))          # let the floor settle
    at = feed(ear, frames(30, 0.3), start=at)   # someone speaks
    feed(ear, frames(30, 0.001), start=at)      # and stops

    assert calls["vad"] > 0
    assert heard == ["почини тесты"]


def test_the_vad_can_veto_what_the_energy_gate_let_through():
    # A door closing has energy and is not speech. Stage 0 is meant to be wrong
    # often; stage 1 is what makes that acceptable.
    ear, calls, heard = listener(is_speech=False)

    at = feed(ear, frames(200, 0.001))
    feed(ear, frames(60, 0.3), start=at)

    assert calls["vad"] > 0
    assert calls["recognise"] == 0
    assert heard == []


def test_an_empty_transcript_is_not_an_utterance():
    # Recognition of a cough returns nothing useful. Passing it on would dispatch
    # an empty task to an agent.
    ear, calls, heard = listener(text="   ")

    at = feed(ear, frames(200, 0.001))
    at = feed(ear, frames(30, 0.3), start=at)
    feed(ear, frames(30, 0.001), start=at)

    assert calls["recognise"] == 1
    assert heard == []


def test_recognition_that_throws_does_not_stop_the_ear():
    # A model that failed once must not leave the application deaf for the rest
    # of the session.
    state = {"first": True}

    def flaky(audio):
        if state["first"]:
            state["first"] = False
            raise RuntimeError("model went away")
        return "второй раз"

    ear, _, heard = listener(recogniser=flaky)

    at = feed(ear, frames(200, 0.001))
    at = feed(ear, frames(30, 0.3), start=at)
    at = feed(ear, frames(30, 0.001), start=at)

    at = feed(ear, frames(30, 0.3), start=at)
    feed(ear, frames(30, 0.001), start=at)

    assert heard == ["второй раз"]


def test_the_vad_is_only_asked_about_frames_the_gate_opened():
    # Stage 1 costs a millisecond a frame. Running it on silence is the whole
    # expense the cascade exists to avoid.
    ear, calls, _ = listener(is_speech=True)

    at = feed(ear, frames(200, 0.001))
    quiet_calls = calls["vad"]
    feed(ear, frames(20, 0.3), start=at)

    assert quiet_calls == 0
    assert calls["vad"] <= 20


class TestWakeWord:
    """Stage 2. Without it, everything said in the room becomes a command."""

    def wired(self, wake_frames=(), arm_seconds=8.0, **kwargs):
        """A listener whose wake word fires on the given frame indices."""
        seen = {"index": 0, "wake_calls": 0}
        heard, woke = [], []

        def is_wake(frame):
            fires = seen["index"] in wake_frames
            seen["index"] += 1
            seen["wake_calls"] += 1
            return fires

        return Listener(
            is_speech=lambda frame: True,
            recognise=lambda audio: "почини тесты",
            on_utterance=heard.append,
            is_wake=is_wake,
            on_wake=lambda at: woke.append(at),
            arm_seconds=arm_seconds,
            tail_seconds=0.5,
            **kwargs,
        ), seen, heard, woke

    def test_speech_without_the_wake_word_is_not_a_command(self):
        # The whole point. A conversation in the room must not reach an agent.
        ear, _, heard, woke = self.wired(wake_frames=())

        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(30, 0.3), start=at)
        feed(ear, frames(30, 0.001), start=at)

        assert heard == []
        assert woke == []

    def test_the_wake_word_opens_the_next_utterance(self):
        ear, _, heard, woke = self.wired(wake_frames=(0,))

        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(30, 0.3), start=at)
        feed(ear, frames(30, 0.001), start=at)

        assert woke, "the wake word never fired"
        assert heard == ["почини тесты"]

    def test_being_awake_expires(self):
        # An assistant that stays armed after one greeting is an assistant that
        # dispatches the conversation that happens to follow it.
        ear, _, heard, _ = self.wired(wake_frames=(0,), arm_seconds=0.2)

        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(3, 0.3), start=at)      # wake fires here
        at = feed(ear, frames(30, 0.001), start=at)   # a long pause
        at = feed(ear, frames(30, 0.3), start=at)     # someone talks again
        feed(ear, frames(30, 0.001), start=at)

        assert heard == []

    def test_the_wake_word_is_not_asked_about_silence(self):
        # Stage 2 costs about two milliseconds a window. Running it on a quiet
        # room is exactly the expense the cascade exists to avoid.
        ear, seen, _, _ = self.wired(wake_frames=())

        feed(ear, frames(300, 0.001))

        assert seen["wake_calls"] == 0

    def test_without_a_detector_every_utterance_counts(self):
        # This is the confirmation path: after Aura asks a question, "yes" is an
        # answer and requiring the wake word again would be absurd.
        heard = []
        ear = Listener(
            is_speech=lambda frame: True,
            recognise=lambda audio: "да",
            on_utterance=heard.append,
            tail_seconds=0.5,
        )

        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(30, 0.3), start=at)
        feed(ear, frames(30, 0.001), start=at)

        assert heard == ["да"]


class TestSpeakerVerification:
    """Stage 3. What stops a television from giving Aura orders."""

    def wired(self, owner=True, wake=True):
        heard, rejected, recognised = [], [], []

        def recognise(audio):
            recognised.append(audio)
            return "почини тесты"

        ear = Listener(
            is_speech=lambda frame: True,
            recognise=recognise,
            on_utterance=heard.append,
            is_wake=(lambda frame: wake),
            is_owner=lambda audio: owner,
            on_rejected=lambda: rejected.append(True),
            tail_seconds=0.5,
        )
        return ear, heard, rejected, recognised

    def speak(self, ear):
        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(30, 0.3), start=at)
        feed(ear, frames(30, 0.001), start=at)

    def test_the_owner_is_heard(self):
        ear, heard, rejected, _ = self.wired(owner=True)

        self.speak(ear)

        assert heard == ["почини тесты"]
        assert rejected == []

    def test_a_stranger_is_refused(self):
        ear, heard, rejected, _ = self.wired(owner=False)

        self.speak(ear)

        assert heard == []
        assert rejected == [True]

    def test_a_stranger_is_never_transcribed(self):
        # Refusing after recognition would still have sent a stranger's words
        # through a model and into a log. The check comes first.
        ear, _, _, recognised = self.wired(owner=False)

        self.speak(ear)

        assert recognised == []

    def test_the_check_runs_after_the_wake_word_not_before(self):
        # Stage 3 costs about fifteen milliseconds an utterance and stage 2 is
        # what makes it rare. Verifying every utterance in the room would spend
        # that on every conversation.
        asked = []
        ear = Listener(
            is_speech=lambda frame: True,
            recognise=lambda audio: "x",
            on_utterance=lambda text: None,
            is_wake=lambda frame: False,
            is_owner=lambda audio: asked.append(True) or True,
            tail_seconds=0.5,
        )

        self.speak(ear)

        assert asked == []

    def test_an_exception_in_verification_refuses_and_recovers(self):
        # A verifier that fails once must not leave the application deaf. The
        # failure is treated as a refusal — a stranger's words do not travel
        # through a model — and the next utterance is handled normally.
        state = {"first": True}

        def flaky_verify(audio):
            if state["first"]:
                state["first"] = False
                raise RuntimeError("model went away")
            return True

        heard, rejected, recognised = [], [], []

        def recognise(audio):
            recognised.append(audio)
            return "восстановлен"

        ear = Listener(
            is_speech=lambda frame: True,
            recognise=recognise,
            on_utterance=heard.append,
            is_wake=lambda frame: True,
            is_owner=flaky_verify,
            on_rejected=lambda: rejected.append(True),
            tail_seconds=0.5,
        )

        at = feed(ear, frames(200, 0.001))
        at = feed(ear, frames(30, 0.3), start=at)
        at = feed(ear, frames(30, 0.001), start=at)

        at = feed(ear, frames(30, 0.3), start=at)
        feed(ear, frames(30, 0.001), start=at)

        assert rejected == [True]
        assert len(recognised) == 1
        assert heard == ["восстановлен"]
