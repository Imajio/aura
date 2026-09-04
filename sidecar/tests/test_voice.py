"""The voice: text in, playable audio out, and a way to stop it mid-sentence.

None of these play anything. Playback is injected, because a test suite that
makes noise is a test suite people stop running.
"""

import pathlib
import struct
import sys
import wave
import io

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.voice import Voice  # noqa: E402


def fake_synth(rate=24000, seconds=0.5):
    """Stands in for Silero: returns a ramp of the requested length."""
    def synthesize(text: str, voice: str) -> tuple[list[float], int]:
        count = int(rate * seconds)
        return [i / count for i in range(count)], rate
    return synthesize


def voice(**kwargs):
    played, cancelled = [], []
    v = Voice(synthesize=kwargs.pop("synthesize", fake_synth()),
              play=played.append,
              stop=lambda: cancelled.append(True),
              **kwargs)
    return v, played, cancelled


def test_rendered_audio_is_a_wav_a_player_will_accept():
    v, _, _ = voice()

    data = v.render("привет")

    with wave.open(io.BytesIO(data), "rb") as f:
        assert f.getnchannels() == 1
        assert f.getsampwidth() == 2
        assert f.getframerate() == 24000
        assert f.getnframes() > 0


def test_speaking_hands_the_player_exactly_what_was_rendered():
    v, played, _ = voice()

    v.speak("привет")

    assert len(played) == 1
    assert played[0].startswith(b"RIFF")


def test_empty_text_plays_nothing():
    v, played, _ = voice()

    v.speak("   ")

    assert played == []


def test_cancel_stops_whatever_is_playing():
    v, _, cancelled = voice()

    v.speak("привет")
    v.cancel()

    assert cancelled == [True]


def test_cancel_is_safe_when_nothing_is_playing():
    v, _, cancelled = voice()

    v.cancel()

    assert cancelled == [True]


def test_a_broken_synthesiser_raises_rather_than_playing_noise():
    def explode(text, voice):
        raise RuntimeError("model went away")

    v, played, _ = voice(synthesize=explode)

    with pytest.raises(RuntimeError):
        v.speak("привет")
    assert played == []


def test_samples_are_clipped_not_wrapped():
    # A sample above 1.0 wrapped into int16 becomes a loud crack in the user's
    # ear. Clipping is quieter and wrong; wrapping is louder and wrong.
    def hot(text, voice):
        return [2.0, -2.0, 0.0], 24000

    v, _, _ = voice(synthesize=hot)

    with wave.open(io.BytesIO(v.render("x")), "rb") as f:
        samples = struct.unpack("<3h", f.readframes(3))
    assert samples == (32767, -32767, 0)


def test_the_configured_voice_reaches_the_synthesiser():
    seen = []

    def synthesize(text, voice):
        seen.append(voice)
        return [0.0], 24000

    v, _, _ = voice(synthesize=synthesize, name="baya")
    v.render("x")

    assert seen == ["baya"]
