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


from aura_speech.recording import (  # noqa: E402
    loudness, next_take_number, record_take, take_path, write_take)


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

    first = next_take_number(out, args.kind)
    if first > 1:
        print(f"{first - 1} take(s) already in {out}; these will be added, "
              f"numbered from {first}.")
        print()

    written, quiet_takes = 0, 0
    for offset in range(args.takes):
        number = first + offset
        for count in (3, 2, 1):
            print(f"\r  take {offset + 1}/{args.takes} in {count}… ", end="", flush=True)
            time.sleep(1)
        print("\r  " + " " * 40 + "\r  recording… ", end="", flush=True)

        samples = record_take(spec["seconds"], args.device)
        level = loudness(samples)
        path = take_path(out, args.kind, number)
        write_take(path, samples)
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
