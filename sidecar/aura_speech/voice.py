"""Speaks a line, and stops speaking when told to.

Synthesis and playback are kept apart on purpose. Rendering is pure — text in,
WAV bytes out — so it can be tested, cached and measured without a sound card,
and so a test suite never makes noise. Playback is the only part that touches
the machine.

Windows' own `winsound` does the playing: it takes WAV bytes straight from
memory, plays them without blocking, and can be told to stop. That is the whole
requirement, and it costs no dependency in a process that is meant to sit idle
all day.

**Stopping matters more than it looks.** The stop word has to interrupt
narration that is already playing — that is the one control the user has when
the application is talking over them — so playback is asynchronous and
`cancel()` is always available, never conditional on bookkeeping about what is
currently playing.
"""

import io
import wave

DEFAULT_VOICE = "xenia"


def _play(data: bytes) -> None:
    import winsound
    # Asynchronous: a narrator that blocks its own protocol loop until it stops
    # talking cannot be interrupted, and being interruptible is the point.
    winsound.PlaySound(data, winsound.SND_MEMORY | winsound.SND_ASYNC)


def _stop() -> None:
    import winsound
    winsound.PlaySound(None, winsound.SND_PURGE)


def silero(release: str = "v4_ru", threads: int = 4):
    """Returns `synthesize(text, voice) -> (samples, rate)`, loading on first use.

    v4 rather than v5: about 110 ms a phrase against 310 ms, both on the CPU,
    and which of the ten voices ships is decided by ear, not here.

    **This loader needs torch, and the runtime environment deliberately has
    none.** Silero publishes its Russian voices only as torch archives — there
    is no ONNX build of them — so speaking through this path today means either
    putting torch in the always-on process or converting the model to OpenVINO
    IR at export time, the way Whisper already is. That choice is open; until it
    is made, `speak` in the runtime environment answers SPEECH_FAILED naming the
    missing module, which is the truth and reaches the tray.
    """
    state = {}

    def synthesize(text: str, voice: str):
        if "model" not in state:
            import torch
            torch.set_num_threads(threads)
            model, _ = torch.hub.load(repo_or_dir="snakers4/silero-models",
                                      model="silero_tts", language="ru",
                                      speaker=release, trust_repo=True)
            state["model"] = model
        # 24 kHz: Silero offers 8, 24 and 48, and nothing here needs more.
        audio = state["model"].apply_tts(text=text, speaker=voice, sample_rate=24000)
        return audio.tolist(), 24000

    return synthesize


class Voice:
    """Renders a line to audio and plays it."""

    def __init__(self, synthesize, play=_play, stop=_stop, name: str = DEFAULT_VOICE):
        self._synthesize = synthesize
        self._play = play
        self._stop = stop
        self._name = name

    def render(self, text: str) -> bytes:
        """A complete WAV file in memory, ready for any player."""
        samples, rate = self._synthesize(text, self._name)
        return _wav(samples, rate)

    def speak(self, text: str) -> None:
        if not text or not text.strip():
            # Nothing to say. Playing an empty buffer would still stop whatever
            # is currently playing, which is a surprising way to lose a sentence.
            return
        self._play(self.render(text))

    def cancel(self) -> None:
        """Unconditional: there is no state here worth being wrong about."""
        self._stop()


def _wav(samples, rate: int) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(rate)
        f.writeframes(_pcm(samples))
    return buffer.getvalue()


def _pcm(samples) -> bytes:
    import struct
    # Clipped, not wrapped. A sample above 1.0 wrapped into int16 arrives in the
    # user's ear as a crack at full volume; clipping is merely wrong.
    clipped = [max(-1.0, min(1.0, float(s))) for s in samples]
    return struct.pack(f"<{len(clipped)}h", *(int(s * 32767) for s in clipped))
