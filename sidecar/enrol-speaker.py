"""Turns the owner's reference recordings into the one embedding Aura compares against.

Run after `record-voice-samples.py reference`. Reads every WAV in the reference
directory, embeds each, averages them, and writes `voice/reference.npy`.

    .venv/Scripts/python.exe enrol-speaker.py

The average of several takes is deliberately not one take: a single recording
carries whatever that moment sounded like - a cold, a close microphone - and the
threshold then measures the moment rather than the person.
"""

import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from aura_speech.training import enrol                   # noqa: E402

DEFAULT_VOICE = pathlib.Path(__file__).resolve().parents[1] / "voice"
DEFAULT_MODEL = (pathlib.Path(__file__).resolve().parents[1]
                 / "models" / "wespeaker-resnet34" / "voxceleb_resnet34_LM.onnx")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--takes", default=str(DEFAULT_VOICE / "reference"))
    parser.add_argument("--model", default=str(DEFAULT_MODEL))
    parser.add_argument("--out", default=str(DEFAULT_VOICE / "reference.npy"))
    args = parser.parse_args(argv)

    try:
        result = enrol(args.takes, args.model, args.out,
                       progress=lambda stage, done, total, detail:
                           print(f"  {done}/{total} {detail}"))
    except ValueError as e:
        raise SystemExit(str(e))

    print(f"enrolled from {result['takes']} take(s) -> {result['out']}")
    if result["warning"]:
        print(f"  warning: {result['warning']}")
    for name, similarity in result["similarities"]:
        print(f"  {name}: {similarity:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
