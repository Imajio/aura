"""Records the samples the wake word and the speaker check are built from.

Two things Aura cannot have until somebody says them out loud:

* **the wake word**, in enough takes and enough moods that a model trained on
  them recognises it on a bad morning as well as a good one;
* **a speaker reference**, so the application can tell its owner from a
  television.

This records. It says so before it starts, it counts down, and every file it
writes is under `voice/`, which is ignored by git and never leaves the machine
— that is stated in the repository's own rules and this is the script that has
to honour it.

    .venv/Scripts/python.exe record-voice-samples.py wake      --takes 20
    .venv/Scripts/python.exe record-voice-samples.py reference --takes 3

Nothing here trains anything. Recording is the part that needs a person and a
quiet room; the training runs afterwards on what this leaves behind.
"""

import argparse
import pathlib
import sys
import time
import wave

import numpy as np

RATE = 16000
DEFAULT_OUT = pathlib.Path(__file__).resolve().parents[1] / "voice"

WHAT = {
    "wake": {
        "say": "Аура",
        "seconds": 2.0,
        "advice": [
            "Say it the way you actually would — not the way you would read it aloud.",
            "Vary it: closer and further from the laptop, sitting and standing,",
            "quietly, in a hurry, mid-sentence. A model trained on twenty identical",
            "takes recognises one mood and misses every other.",
        ],
    },
    "reference": {
        "say": "any sentence you like, in your normal voice",
        "seconds": 6.0,
        "advice": [
            "Ordinary speech, not the wake word. This is what tells you apart from",
            "whoever else is in the room, and it needs your voice being itself.",
            "A few seconds of a real sentence beats a careful recitation.",
        ],
    },
}


def record(seconds: float, device_index=None) -> np.ndarray:
    """One take, from the microphone, at 16 kHz mono."""
    import pyaudiowpatch as pyaudio

    from aura_speech.hearing import _wasapi_input, to_16k

    audio = pyaudio.PyAudio()
    try:
        index, rate = ((device_index, int(audio.get_device_info_by_index(device_index)
                                          ["defaultSampleRate"]))
                       if device_index is not None else _wasapi_input(audio, pyaudio))
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


def write(path: pathlib.Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(pcm.tobytes())


def loudness(samples: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=sorted(WHAT))
    parser.add_argument("--takes", type=int, default=20)
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--device", type=int, default=None)
    args = parser.parse_args(argv)

    spec = WHAT[args.kind]
    out = pathlib.Path(args.out) / args.kind

    print(f"About to record {args.takes} take(s) of {spec['seconds']:.0f} seconds each.")
    print(f"Say: {spec['say']}")
    print()
    for line in spec["advice"]:
        print("  " + line)
    print()
    print(f"Files go to {out}. That directory is ignored by git and stays on this")
    print("machine; nothing here uploads anything.")
    print()
    if input("Press Enter to start, or type anything to cancel: ").strip():
        print("Cancelled. Nothing was recorded.")
        return 1

    written, quiet_takes = 0, 0
    for take in range(1, args.takes + 1):
        for count in (3, 2, 1):
            print(f"\r  take {take}/{args.takes} in {count}… ", end="", flush=True)
            time.sleep(1)
        print("\r  " + " " * 40 + "\r  recording… ", end="", flush=True)

        samples = record(spec["seconds"], args.device)
        level = loudness(samples)
        path = out / f"{args.kind}-{take:03d}.wav"
        write(path, samples)
        written += 1

        # Said plainly and immediately: a take too quiet to hear is worse than a
        # missing one, because it is silently trained on.
        if level < 0.005:
            quiet_takes += 1
            print(f"very quiet ({level:.4f}) — move closer, or record this one again")
        else:
            print(f"ok ({level:.3f})  {path.name}")

    print()
    print(f"{written} file(s) in {out}")
    if quiet_takes:
        print(f"{quiet_takes} of them were very quiet. Delete those and run again "
              f"for the same number of takes.")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    raise SystemExit(main())
