"""The cheap end of the cascade: the part that decides not to run a neural network.

Stage 0 is the only stage alive in a quiet room, and the PRD states that as a
measurable requirement rather than a wish. Everything here is arithmetic over
frames, with time passed in rather than read, so a test can walk a noise floor
through a minute of audio in a millisecond.
"""

import pathlib
import sys

import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.cascade import EnergyGate, NoiseFloor, Segmenter  # noqa: E402

RATE = 16000
FRAME = 512                      # 32 ms, the window Silero VAD expects
FRAME_SECONDS = FRAME / RATE


def quiet(level=0.001):
    rng = np.random.default_rng(0)
    return (rng.standard_normal(FRAME) * level).astype(np.float32)


def loud(level=0.2):
    rng = np.random.default_rng(1)
    return (rng.standard_normal(FRAME) * level).astype(np.float32)


class TestNoiseFloor:

    def test_it_learns_the_room(self):
        floor = NoiseFloor(window_seconds=30.0, margin_db=6.0)
        for i in range(100):
            floor.update(0.01, at=i * FRAME_SECONDS)

        assert floor.level() == 0.01
        # Six decibels above the floor is very nearly twice its amplitude —
        # 10^(6/20) = 1.995, not 2, which is the sort of rounding that only shows
        # up when a test asserts equality on it.
        assert floor.threshold() == pytest.approx(0.01 * 10 ** 0.3, rel=1e-6)

    def test_a_shout_does_not_become_the_floor(self):
        # The floor is what the room is like when nobody is talking. Taking the
        # loudest thing heard would raise it until nothing opens the gate again.
        floor = NoiseFloor(window_seconds=30.0)
        for i in range(50):
            floor.update(0.01, at=i * FRAME_SECONDS)
        floor.update(0.9, at=50 * FRAME_SECONDS)

        assert floor.level() < 0.02

    def test_it_forgets_a_room_that_has_gone_quiet(self):
        # A fan switched off, a window closed: the floor has to come down, or the
        # gate stays shut and the application goes deaf.
        floor = NoiseFloor(window_seconds=10.0)
        for i in range(400):
            floor.update(0.05, at=i * FRAME_SECONDS)
        noisy = floor.level()

        for i in range(400, 1200):
            floor.update(0.001, at=i * FRAME_SECONDS)

        assert floor.level() < noisy / 10

    def test_it_has_an_opinion_before_it_has_data(self):
        # The first frame after start-up must not open the gate on nothing, and
        # must not shut it forever either.
        floor = NoiseFloor()
        assert floor.threshold() > 0


class TestEnergyGate:

    def test_a_quiet_room_keeps_it_shut(self):
        gate = EnergyGate(NoiseFloor(window_seconds=30.0))
        opened = [gate.open(quiet(), at=i * FRAME_SECONDS) for i in range(200)]

        assert not any(opened)

    def test_speech_opens_it(self):
        gate = EnergyGate(NoiseFloor(window_seconds=30.0))
        for i in range(200):
            gate.open(quiet(), at=i * FRAME_SECONDS)

        assert gate.open(loud(), at=200 * FRAME_SECONDS)

    def test_a_noisy_room_still_shuts_it(self):
        # The whole point of an adaptive floor: next to a fan the gate must not
        # sit open, or every stage behind it runs continuously and the cascade
        # has bought nothing.
        gate = EnergyGate(NoiseFloor(window_seconds=30.0))
        for i in range(400):
            gate.open(loud(0.05), at=i * FRAME_SECONDS)

        later = [gate.open(loud(0.05), at=(400 + i) * FRAME_SECONDS) for i in range(50)]
        assert not any(later)


class TestSegmenter:

    def utterance(self, segmenter, speech_frames, silence_frames, start=0):
        """Feeds speech then silence, returning whatever came out."""
        out = []
        at = start
        for _ in range(speech_frames):
            out.append(segmenter.push(loud(), True, at))
            at += FRAME_SECONDS
        for _ in range(silence_frames):
            out.append(segmenter.push(quiet(), False, at))
            at += FRAME_SECONDS
        return [o for o in out if o is not None]

    def test_speech_is_not_cut_at_the_first_pause(self):
        # People pause inside sentences. A tail shorter than the pause turns one
        # command into two halves, and neither half means anything.
        segmenter = Segmenter(tail_seconds=0.5)
        finished = self.utterance(segmenter, speech_frames=30, silence_frames=5)

        assert finished == []

    def test_the_utterance_ends_after_the_tail(self):
        segmenter = Segmenter(tail_seconds=0.5)
        finished = self.utterance(segmenter, speech_frames=30, silence_frames=20)

        assert len(finished) == 1
        assert len(finished[0]) >= 30 * FRAME

    def test_the_tail_is_carried_with_the_utterance(self):
        # Whisper hears the end of a word better with a little silence after it.
        segmenter = Segmenter(tail_seconds=0.5)
        finished = self.utterance(segmenter, speech_frames=30, silence_frames=20)

        assert len(finished[0]) > 30 * FRAME

    def test_silence_alone_produces_nothing(self):
        segmenter = Segmenter(tail_seconds=0.5)
        out = [segmenter.push(quiet(), False, i * FRAME_SECONDS) for i in range(200)]

        assert all(o is None for o in out)

    def test_an_open_microphone_is_cut_off_rather_than_transcribed_whole(self):
        # The television case. Thirty seconds is a long command and an infinite
        # recording is not a command at all.
        segmenter = Segmenter(tail_seconds=0.5, max_seconds=1.0)
        at = 0.0
        finished = []
        for _ in range(200):
            out = segmenter.push(loud(), True, at)
            if out is not None:
                finished.append(out)
            at += FRAME_SECONDS

        assert finished, "endless speech was never cut"
        assert len(finished[0]) <= int(1.0 * RATE) + FRAME

    def test_two_utterances_are_two(self):
        segmenter = Segmenter(tail_seconds=0.5)
        first = self.utterance(segmenter, 30, 20, start=0.0)
        second = self.utterance(segmenter, 30, 20, start=10.0)

        assert len(first) == 1
        assert len(second) == 1


class TestRateConversion:
    """The microphone rarely offers 16 kHz, and guessing which rate it offers is
    how audio becomes noise that a VAD still answers about, confidently."""

    def test_an_integer_ratio_is_averaged(self):
        from aura_speech.hearing import to_16k
        block = np.ones(FRAME * 3, dtype=np.float32)
        assert len(to_16k(block, 48000)) == FRAME
        assert np.allclose(to_16k(block, 48000), 1.0)

    def test_an_awkward_rate_is_interpolated_not_assumed(self):
        # 44.1 kHz is 2.75 times 16 kHz. The default input device on the machine
        # this was written on reports exactly that.
        from aura_speech.hearing import to_16k
        block = np.linspace(0, 1, round(FRAME * 44100 / RATE)).astype(np.float32)
        out = to_16k(block, 44100)
        assert len(out) == FRAME
        assert out[0] == pytest.approx(0.0, abs=1e-6)
        assert out[-1] == pytest.approx(1.0, abs=1e-6)

    def test_the_native_rate_passes_through(self):
        from aura_speech.hearing import to_16k
        block = np.arange(FRAME, dtype=np.float32)
        assert np.array_equal(to_16k(block, RATE), block)
