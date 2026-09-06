"""Capturing one take and putting it somewhere it will not destroy another.

Recording is the only thing in this project that produces something no program
can make again. Everything else — a model, an embedding, a transcript — can be
rebuilt from what is on disk; a take of somebody's voice cannot. So the numbering
lives here, in one function, tested from a populated directory, rather than in a
loop in whichever script happens to be recording.

Capture and playback stay apart from the file handling for the same reason
`voice.py` keeps rendering apart from playing: the part that needs a microphone
cannot be tested, and the part that decides where a file goes must be.
"""

import pathlib
import re
import wave

import numpy as np

RATE = 16000
_TAKE = re.compile(r"^(?P<kind>[a-z]+)-(?P<number>\d+)\.wav$")


def take_path(directory, kind: str, number: int) -> pathlib.Path:
    """Where take `number` of `kind` lives. The one place the name is formed."""
    return pathlib.Path(directory) / f"{kind}-{number:03d}.wav"


def next_take_number(directory, kind: str) -> int:
    """One past the highest take already there — never a gap, never a reuse.

    Filling a gap left by a deleted take would give a new recording a name an
    old one had, which is how a note saying "take 2 was the quiet one" starts
    lying. Counting from the highest costs nothing and never does that.
    """
    directory = pathlib.Path(directory)
    if not directory.is_dir():
        return 1
    highest = 0
    for entry in directory.iterdir():
        match = _TAKE.match(entry.name)
        if match and match.group("kind") == kind:
            highest = max(highest, int(match.group("number")))
    return highest + 1


def write_take(path, samples: np.ndarray) -> None:
    """One take as a 16 kHz mono WAV, clipped rather than wrapped."""
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(pcm.tobytes())


def loudness(samples: np.ndarray) -> float:
    """Root mean square, for telling a take that was too quiet to train on."""
    return float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))


def record_take(seconds: float, device_index=None) -> np.ndarray:
    """One take from the microphone, 16 kHz mono.

    **This opens the microphone.** It is called from exactly two places — the
    recording script and the sidecar's `record` command — and both of them say
    so to the person first.
    """
    import pyaudiowpatch as pyaudio

    from .hearing import _wasapi_input, to_16k

    audio = pyaudio.PyAudio()
    try:
        if device_index is not None:
            index = device_index
            rate = int(audio.get_device_info_by_index(index)["defaultSampleRate"])
        else:
            index, rate = _wasapi_input(audio, pyaudio)
        block = 1024
        stream = audio.open(format=pyaudio.paFloat32, channels=1, rate=rate,
                            input=True, input_device_index=index,
                            frames_per_buffer=block)
        try:
            wanted = int(seconds * rate)
            captured = []
            while sum(len(c) for c in captured) < wanted:
                raw = stream.read(block, exception_on_overflow=False)
                captured.append(np.frombuffer(raw, dtype=np.float32))
            native = np.concatenate(captured)[:wanted]
        finally:
            stream.stop_stream()
            stream.close()
    finally:
        audio.terminate()

    frames = max(1, round(len(native) * RATE / rate))
    return to_16k(native, rate, frames)
