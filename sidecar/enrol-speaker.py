"""Turns the owner's reference recordings into the one embedding Aura compares against.

Run after `record-voice-samples.py reference`. Reads every WAV in the reference
directory, embeds each, averages them, and writes `voice/reference.npy`.

    .venv/Scripts/python.exe enrol-speaker.py

The average of several takes is deliberately not one take: a single recording
carries whatever that moment sounded like — a cold, a close microphone — and the
threshold then measures the moment rather than the person.
"""

import argparse
import pathlib
import sys
import wave

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from aura_speech.hearing import speaker_session          # noqa: E402
from aura_speech.speaker import Enrolment, embed         # noqa: E402

DEFAULT_VOICE = pathlib.Path(__file__).resolve().parents[1] / "voice"
DEFAULT_MODEL = (pathlib.Path(__file__).resolve().parents[1]
                 / "models" / "wespeaker-resnet34" / "voxceleb_resnet34_LM.onnx")


def read_wav(path: pathlib.Path) -> np.ndarray:
    with wave.open(str(path), "rb") as f:
        if f.getframerate() != 16000 or f.getnchannels() != 1:
            raise SystemExit(f"{path}: expected 16 kHz mono")
        frames = f.readframes(f.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--takes", default=str(DEFAULT_VOICE / "reference"))
    parser.add_argument("--model", default=str(DEFAULT_MODEL))
    parser.add_argument("--out", default=str(DEFAULT_VOICE / "reference.npy"))
    args = parser.parse_args(argv)

    takes = sorted(pathlib.Path(args.takes).glob("*.wav"))
    if not takes:
        raise SystemExit(
            f"no recordings in {args.takes} — run record-voice-samples.py reference first")
    model = pathlib.Path(args.model)
    if not model.is_file():
        raise SystemExit(f"no speaker model at {model}")

    session = speaker_session(model)
    embeddings = [embed(read_wav(path), session) for path in takes]
    enrolment = Enrolment.from_embeddings(embeddings)
    enrolment.save(args.out)

    print(f"enrolled from {len(takes)} take(s) -> {args.out}")
    # Printed because it is the number that says whether the takes were
    # consistent. Two takes of the same person should agree well above the
    # threshold; if they do not, the recordings are the problem, not the model.
    for path, embedding in zip(takes, embeddings):
        print(f"  {path.name}: {enrolment.similarity(embedding):.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
