"""The voice: text in, playable audio out, and a way to stop it mid-sentence.

None of these play anything. Playback is injected, because a test suite that
makes noise is a test suite people stop running.
"""

import pathlib
import struct
import sys
import time
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


class TestTheRealPlayer:
    """`_play` itself - the one part every other test in this file injects away.

    It was wrong from the day it was written and no test could see it: every
    check above hands `Voice` a fake player, so the default was exercised for
    the first time by a person listening for narration and hearing silence.
    """

    def winsound(self, monkeypatch):
        """A stand-in for winsound that records the call and refuses what CPython refuses."""
        import types
        calls = []
        module = types.SimpleNamespace(
            SND_MEMORY=4, SND_ASYNC=1, SND_PURGE=0x40, SND_FILENAME=0x20020000)

        def play_sound(sound, flags):
            # The real module raises exactly here, which is what shipped:
            # "RuntimeError: Cannot play asynchronously from memory".
            if (flags & module.SND_ASYNC) and (flags & module.SND_MEMORY):
                raise RuntimeError("Cannot play asynchronously from memory")
            calls.append((sound, flags))

        module.PlaySound = play_sound
        monkeypatch.setitem(sys.modules, "winsound", module)
        return module, calls

    def test_playing_from_memory_is_never_asked_to_be_asynchronous(self, monkeypatch):
        import threading
        from aura_speech.voice import _play

        module, calls = self.winsound(monkeypatch)
        before = threading.active_count()

        _play(b"RIFFfake")

        # The thread is what keeps the protocol loop free now that the flag cannot.
        deadline = time.monotonic() + 2.0
        while not calls and time.monotonic() < deadline:
            time.sleep(0.01)

        assert calls, "nothing was ever played"
        sound, flags = calls[0]
        assert sound == b"RIFFfake"
        assert flags & module.SND_MEMORY
        assert not flags & module.SND_ASYNC
        assert before <= threading.active_count() + 1

    def test_a_sound_card_that_fails_does_not_kill_the_process(self, monkeypatch):
        import types
        from aura_speech.voice import _play

        module = types.SimpleNamespace(SND_MEMORY=4, SND_ASYNC=1)

        def explode(sound, flags):
            raise RuntimeError("device disappeared")

        module.PlaySound = explode
        monkeypatch.setitem(sys.modules, "winsound", module)

        _play(b"RIFFfake")   # the failure happens on the player's own thread
        time.sleep(0.2)      # long enough for it to have happened
