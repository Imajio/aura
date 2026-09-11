"""Compares what two recognition models hear, on Russian commands.

Latency is the easy half of choosing a recogniser and it is already measured.
The half that decides whether the product works is what the model actually
writes down when the user speaks Russian with English technical terms in it -
"в проекте backend почини падающие тесты". A model that answers in 180 ms and
mishears the project name is not faster; it is wrong sooner.

The speech comes from Silero, because there is no recorded corpus yet. That is
a real limitation and it cuts one way: synthesised speech is cleaner than a
person at a laptop, so every number here is optimistic. It is still a fair
*comparison* - both models hear the identical audio - and a model that fails on
clean synthetic Russian will not do better on the real thing.

    .venv-export/Scripts/python.exe compare-recognition.py \\
        --models ../models/whisper-tiny-int8,../models/whisper-large-v3-turbo-int8

Replace this with recordings of the owner's own voice as soon as there are any:
the corpus in testdata/audio exists for exactly that, and this script's verdict
should not outlive it.
"""

import argparse
import pathlib
import re
import tempfile
import time
import wave

import numpy as np

# The commands the product is for: Russian speech carrying English technical
# terms, project names and tool names - the case a small multilingual model is
# most likely to break on.
COMMANDS = [
    "в проекте бэкенд почини падающие тесты",
    "запусти тесты в модуле авторизации",
    "в проекте фронтенд обнови зависимости",
    "покажи что не так с последним коммитом",
    "останови агента",
    "в проекте бэкенд выполни команду dir",
]


def resample(samples: np.ndarray, source: int, target: int) -> np.ndarray:
    """Rate conversion good enough for a like-for-like comparison.

    scipy's polyphase filter when it is available, linear interpolation when it
    is not. Both models are fed the identical result either way, so the choice
    changes the absolute numbers slightly and the comparison not at all.
    """
    try:
        from scipy.signal import resample_poly
        from math import gcd
        divisor = gcd(source, target)
        return resample_poly(samples, target // divisor, source // divisor)
    except ImportError:
        count = int(len(samples) * target / source)
        return np.interp(np.linspace(0, len(samples) - 1, count),
                         np.arange(len(samples)), samples)


def normalise(text: str) -> list[str]:
    return re.sub(r"[^\w\s]", " ", text.lower()).split()


def word_error_rate(reference: str, hypothesis: str) -> float:
    """Levenshtein distance over words, divided by the reference length."""
    r, h = normalise(reference), normalise(hypothesis)
    if not r:
        return 0.0
    previous = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        current = [i]
        for j, hw in enumerate(h, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1,
                               previous[j - 1] + (rw != hw)))
        previous = current
    return previous[-1] / len(r)


def main() -> int:
    here = pathlib.Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", default=str(here.parent / "models" / "whisper-tiny-int8"))
    parser.add_argument("--device", default="GPU")
    parser.add_argument("--cache", default=str(here.parent / ".ov_cache"))
    parser.add_argument("--voice", default="xenia")
    args = parser.parse_args()

    # Silero synthesises at 8/24/48 kHz; Whisper wants 16 kHz. Synthesise at the
    # nearest supported rate and resample once, so both models hear the same
    # audio and neither is handed a resampling artefact the other avoided.
    synth_rate, rate = 24000, 16000

    import openvino_genai
    import torch
    torch.set_num_threads(4)

    tts, _ = torch.hub.load(repo_or_dir="snakers4/silero-models", model="silero_tts",
                            language="ru", speaker="v4_ru", trust_repo=True)

    audio = []
    with tempfile.TemporaryDirectory() as tmp:
        for i, text in enumerate(COMMANDS):
            wav = tts.apply_tts(text=text, speaker=args.voice, sample_rate=synth_rate)
            samples = resample(wav.numpy(), synth_rate, rate)
            path = pathlib.Path(tmp) / f"{i}.wav"
            pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
            with wave.open(str(path), "wb") as f:
                f.setnchannels(1)
                f.setsampwidth(2)
                f.setframerate(rate)
                f.writeframes(pcm.tobytes())
            with wave.open(str(path), "rb") as f:
                frames = f.readframes(f.getnframes())
            audio.append(np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0)

    for spec in [m.strip() for m in args.models.split(",") if m.strip()]:
        model = pathlib.Path(spec)
        if not model.is_dir():
            print(f"\n{spec}: not a directory, skipped")
            continue
        cache = pathlib.Path(args.cache) / args.device
        pipeline = openvino_genai.WhisperPipeline(str(model), device=args.device,
                                                  CACHE_DIR=str(cache))
        config = pipeline.get_generation_config()
        config.language = "<|ru|>"
        config.task = "transcribe"

        pipeline.generate(audio[0], config)  # warm: the first call is not the model's speed

        print(f"\n=== {model.name} ===")
        rates, timings = [], []
        for text, samples in zip(COMMANDS, audio):
            started = time.perf_counter()
            heard = str(pipeline.generate(samples, config)).strip()
            timings.append((time.perf_counter() - started) * 1000.0)
            wer = word_error_rate(text, heard)
            rates.append(wer)
            flag = "  " if wer == 0 else ("~ " if wer < 0.34 else "! ")
            print(f"{flag}{wer * 100:5.0f}%  {heard}")
            if wer:
                print(f"         expected: {text}")
        print(f"  mean WER {sum(rates) / len(rates) * 100:.0f}% · "
              f"mean {sum(timings) / len(timings):.0f} ms")

    print("\nSynthesised speech, so these are optimistic. A model that stumbles here")
    print("will not do better on a person speaking into a laptop microphone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
