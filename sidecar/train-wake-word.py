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
import wave

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from aura_speech.wake import WakeClassifier, wake_features  # noqa: E402

DEFAULT_VOICE = pathlib.Path(__file__).resolve().parents[1] / "voice"
# Same directory as hearing.py's DEFAULT_WAKE_FEATURE_MODELS, computed
# independently through this file's own `parents[N]` — moving either file
# changes what N needs to be here, so keep the pair in mind if you do.
DEFAULT_FEATURE_MODELS = pathlib.Path(__file__).resolve().parents[1] / "models" / "openwakeword"


def read_wav(path: pathlib.Path) -> np.ndarray:
    with wave.open(str(path), "rb") as f:
        rate = f.getframerate()
        frames = f.readframes(f.getnframes())
    samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    if rate != 16000:
        wanted = int(len(samples) * 16000 / rate)
        samples = np.interp(np.linspace(0, len(samples) - 1, wanted),
                            np.arange(len(samples)), samples).astype(np.float32)
    return samples


def embed_all(paths, melspec_session, embedding_session):
    """One embedding vector per clip, through the shared `wake_features`."""
    vectors = []
    for path in paths:
        audio = read_wav(path)
        if len(audio) < 16000:
            audio = np.pad(audio, (0, 16000 - len(audio)))
        # int16 magnitude: see wake_features' docstring for why.
        vectors.append(wake_features(audio * 32767.0, melspec_session, embedding_session))
    return np.stack(vectors) if vectors else np.zeros((0, 0), dtype=np.float32)


def fit(positive: np.ndarray, negative: np.ndarray, steps: int = 2000,
        learning_rate: float = 0.1):
    """Logistic regression by gradient descent. No sklearn: this is twelve lines."""
    features = np.vstack([positive, negative]).astype(np.float32)
    labels = np.concatenate([np.ones(len(positive)), np.zeros(len(negative))])
    mean = features.mean(axis=0)
    scale = features.std(axis=0)
    scale = np.where(scale > 1e-6, scale, 1.0)
    standardised = (features - mean) / scale

    weights = np.zeros(features.shape[1], dtype=np.float32)
    bias = 0.0
    for _ in range(steps):
        predictions = 1.0 / (1.0 + np.exp(-(standardised @ weights + bias)))
        error = predictions - labels
        weights -= learning_rate * (standardised.T @ error) / len(labels)
        bias -= learning_rate * float(error.mean())
    return WakeClassifier(weights, bias, mean, scale)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--takes", default=str(DEFAULT_VOICE / "wake"))
    parser.add_argument("--negative", default=r"C:\Aura\tts-audition")
    parser.add_argument("--out", default=str(DEFAULT_VOICE / "wake-word.npz"))
    args = parser.parse_args(argv)

    takes = sorted(pathlib.Path(args.takes).glob("*.wav"))
    negatives = sorted(pathlib.Path(args.negative).rglob("*.wav"))
    if len(takes) < 5:
        raise SystemExit(
            f"only {len(takes)} recording(s) in {args.takes}; record about twenty "
            f"with record-voice-samples.py wake")
    if not negatives:
        raise SystemExit(
            f"no negative audio in {args.negative}; a classifier trained only on "
            f"the wake word learns to say yes to everything")

    import onnxruntime as ort
    melspec_session = ort.InferenceSession(
        str(DEFAULT_FEATURE_MODELS / "melspectrogram.onnx"),
        providers=["CPUExecutionProvider"])
    embedding_session = ort.InferenceSession(
        str(DEFAULT_FEATURE_MODELS / "embedding_model.onnx"),
        providers=["CPUExecutionProvider"])

    print(f"embedding {len(takes)} take(s) and {len(negatives)} negative clip(s)…")
    positive = embed_all(takes, melspec_session, embedding_session)
    negative = embed_all(negatives, melspec_session, embedding_session)

    classifier = fit(positive, negative)
    classifier.save(args.out)

    # Reported because a training run that fits its own data perfectly and
    # nothing else is the commonest way this goes wrong quietly.
    hits = sum(classifier.score(v) > 0.5 for v in positive)
    false = sum(classifier.score(v) > 0.5 for v in negative)
    print(f"wrote {args.out}")
    print(f"  wake word recognised in {hits}/{len(positive)} of your takes")
    print(f"  fired on {false}/{len(negative)} clips that were not the wake word")
    if false:
        print("  record more negative audio before trusting this")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
