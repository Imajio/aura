"""Turns the owner's recordings of the wake word into a model that recognises it.

Run after `record-voice-samples.py wake`. Embeds every take with the speech
embedding model openWakeWord ships, embeds every negative clip it can find — all
of them, not a number matched to the takes — fits a logistic regression, and
writes `voice/wake-word.npz`.

    .venv/Scripts/python.exe train-wake-word.py

Negative audio matters as much as positive: a classifier trained only on the
wake word learns to say yes to everything. Anything spoken that is not the wake
word will do — the narrator audition samples under `C:/Aura/tts-audition` are a
good start, and background recordings in `recordings/` are better. Every WAV
under that directory is used, with no truncation to match the number of takes,
so the two sides are normally lopsided — about a hundred audition clips against
twenty takes — which pushes the fit towards silence rather than towards false
wakes.

The embedding comes from two of openWakeWord's own ONNX files —
`melspectrogram.onnx` and `embedding_model.onnx` — expected at
`models/openwakeword/`. They are openWakeWord's release assets, placed there by
hand, not installed through the `openwakeword` package: that package pulls in
scipy and scikit-learn to train its own classifiers, about 150 MB, into a
sidecar meant to sit idle all day. `models/` is git-ignored like every other
model in this project, so those two files are a one-time manual step, not
something cloning the repository provides. `wake_features` in
`aura_speech/wake.py` is the one function this script and the live detector in
`hearing.py` both call, so a classifier is always scored on exactly the numbers
it was trained on.
"""

import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from aura_speech.training import train_wake_word            # noqa: E402

DEFAULT_VOICE = pathlib.Path(__file__).resolve().parents[1] / "voice"
# Same directory as hearing.py's DEFAULT_WAKE_FEATURE_MODELS, computed
# independently through this file's own `parents[N]` — moving either file
# changes what N needs to be here, so keep the pair in mind if you do.
DEFAULT_FEATURE_MODELS = pathlib.Path(__file__).resolve().parents[1] / "models" / "openwakeword"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--takes", default=str(DEFAULT_VOICE / "wake"))
    parser.add_argument("--negative", default=r"C:\Aura\tts-audition")
    parser.add_argument("--out", default=str(DEFAULT_VOICE / "wake-word.npz"))
    args = parser.parse_args(argv)

    try:
        result = train_wake_word(args.takes, args.negative, DEFAULT_FEATURE_MODELS, args.out,
                                 progress=lambda stage, done, total, detail:
                                     print(f"  {done}/{total} {detail}"))
    except ValueError as e:
        raise SystemExit(str(e))

    # Reported because a training run that fits its own data perfectly and
    # nothing else is the commonest way this goes wrong quietly.
    print(f"wrote {result['out']}")
    print(f"  wake word recognised in {result['recognised']}/{result['takes']} of your takes")
    print(f"  fired on {result['falsePositives']}/{result['negatives']} clips that were not "
          f"the wake word")
    if result["falsePositives"] / result["negatives"] > 0.1:
        print("  record more negative audio before trusting this")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
