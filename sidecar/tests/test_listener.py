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
