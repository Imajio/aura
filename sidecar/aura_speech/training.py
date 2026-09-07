"""Enrolment and wake-word training, as functions a button can call.

Both used to live inside a script's `main()`, which made them unreachable from
anywhere but a terminal — and the owner's request is precisely to reach them from
a window, as often as they like. So the work moves here and both scripts become
front ends, exactly as `record-voice-samples.py` is a front end over
`recording.py` and as the trainer and the detector already share `wake_features`.

Every failure is a `ValueError` whose message names the file or the number that
is wrong. It is read aloud by nobody but it is shown to somebody, and "no
recordings in C:\\Aura\\aura\\voice\\wake" is a sentence they can act on where
"training failed" is not.
"""

import pathlib
import wave

import numpy as np


def read_wav(path) -> np.ndarray:
    """One take as float32 at 16 kHz, or a `ValueError` naming the file."""
    path = pathlib.Path(path)
    try:
        with wave.open(str(path), "rb") as f:
            rate, channels = f.getframerate(), f.getnchannels()
            if rate != 16000 or channels != 1:
                raise ValueError(
                    f"{path.name}: expected 16 kHz mono, got {rate} Hz / {channels} ch")
            frames = f.readframes(f.getnframes())
    except (wave.Error, EOFError, OSError) as e:
        raise ValueError(f"{path.name}: {e}") from e
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def _takes(directory) -> list:
    directory = pathlib.Path(directory)
    return sorted(directory.glob("*.wav")) if directory.is_dir() else []


def enrol(takes_dir, model_path, out_path, progress, embed_factory=None) -> dict:
    """Averages the reference takes into the one embedding every voice is judged against.

    `embed_factory(model_path) -> embed(audio) -> vector` exists so the decisions
    here — is there anything to enrol, does the model exist, which take could not
    be read — are testable without loading 26 MB of ONNX.
    """
    from .speaker import Enrolment

    takes = _takes(takes_dir)
    if not takes:
        raise ValueError(f"no recordings in {takes_dir} — record a few first")
    model_path = pathlib.Path(model_path)
    if not model_path.is_file():
        raise ValueError(f"no speaker model at {model_path}")

    if embed_factory is None:
        def embed_factory(path):
            from .hearing import speaker_session
            from .speaker import embed as embed_audio
            session = speaker_session(path)
            return lambda audio: embed_audio(audio, session)

    embed = embed_factory(model_path)

    embeddings = []
    for index, path in enumerate(takes, start=1):
        embeddings.append(embed(read_wav(path)))
        progress("enrol", index, len(takes), path.name)

    enrolment = Enrolment.from_embeddings(embeddings)
    enrolment.save(out_path)

    return {
        "takes": len(takes),
        "out": str(out_path),
        "similarities": [(p.name, float(enrolment.similarity(e)))
                         for p, e in zip(takes, embeddings)],
        # One take cannot disagree with anything, so its similarity is 1.000 and
        # says nothing at all. Saying so is the only honest thing to report.
        "warning": ("one recording cannot be checked for consistency — record two "
                    "or three for a better reference") if len(takes) == 1 else "",
    }


def train_wake_word(takes_dir, negative_dir, feature_models, out_path, progress,
                    sessions_factory=None) -> dict:
    """Fits the wake word on the owner's takes against audio that is not it."""
    from .wake import wake_features

    takes = _takes(takes_dir)
    negatives = sorted(pathlib.Path(negative_dir).rglob("*.wav")) \
        if pathlib.Path(negative_dir).is_dir() else []
    if len(takes) < 5:
        raise ValueError(
            f"only {len(takes)} recording(s) in {takes_dir}; about twenty are needed")
    if not negatives:
        raise ValueError(
            f"no negative audio in {negative_dir}; a classifier trained only on the "
            f"wake word learns to say yes to everything")

    feature_models = pathlib.Path(feature_models)
    for name in ("melspectrogram.onnx", "embedding_model.onnx"):
        if not (feature_models / name).is_file():
            raise ValueError(f"no {name} in {feature_models}")

    if sessions_factory is None:
        def sessions_factory(directory):
            import onnxruntime as ort
            return (ort.InferenceSession(str(directory / "melspectrogram.onnx"),
                                         providers=["CPUExecutionProvider"]),
                    ort.InferenceSession(str(directory / "embedding_model.onnx"),
                                         providers=["CPUExecutionProvider"]))

    melspec_session, embedding_session = sessions_factory(feature_models)

    total = len(takes) + len(negatives)
    done = 0
    vectors = {}
    for label, paths in (("positive", takes), ("negative", negatives)):
        rows = []
        for path in paths:
            audio = read_wav(path)
            if len(audio) < 16000:
                audio = np.pad(audio, (0, 16000 - len(audio)))
            rows.append(wake_features(audio * 32767.0, melspec_session, embedding_session))
            done += 1
            progress("embed", done, total, path.name)
        vectors[label] = np.stack(rows)

    progress("fit", total, total, "fitting")
    classifier = _fit(vectors["positive"], vectors["negative"])
    classifier.save(out_path)

    hits = int(sum(classifier.score(v) > 0.5 for v in vectors["positive"]))
    false = int(sum(classifier.score(v) > 0.5 for v in vectors["negative"]))
    return {
        "out": str(out_path),
        "takes": len(takes),
        "negatives": len(negatives),
        "recognised": hits,
        "falsePositives": false,
    }


def _fit(positive: np.ndarray, negative: np.ndarray, steps: int = 2000,
         learning_rate: float = 0.1):
    """Logistic regression by gradient descent. No sklearn: this is twelve lines."""
    from .wake import WakeClassifier

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
