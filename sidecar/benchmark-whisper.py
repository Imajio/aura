"""Measures Whisper recognition latency on a given device.

This is the experiment behind RISK-1. The design gives recognition 550 ms of the
1.2 s end-to-end budget, on an utterance of roughly six seconds — the rest is
the VAD tail, speaker verification and routing. A threshold set against the
whole 1.2 s would call it a success at twice the real overrun, so this script
reports the one number the risk is judged on and nothing else dressed up as it.

Two timings, and they answer different questions:

* **compile** — building the pipeline for the device. Paid once per model per
  driver version, then served from `cache_dir`. On the NPU this is the step that
  fails outright if a model cannot be made static.
* **recognition** — what the user waits for, measured warm and repeated.

    .venv-export/Scripts/python.exe benchmark-whisper.py --device NPU
    .venv-export/Scripts/python.exe benchmark-whisper.py --device GPU --runs 10

Exit codes, because the two failures are not the same finding and a script that
conflates them is worse than no script:

    0   recognition p50 is within the threshold
    1   it compiled and ran, but missed the threshold
    2   it did not compile for the device at all

The blob cache is kept per device. One cache shared between NPU and GPU means
measuring the fallback ladder throws away the previous device's compiled blob
and pays for it again on the next re-measurement.
"""

import argparse
import pathlib
import statistics
import sys
import time
import wave

import numpy as np


def read_wav(path: pathlib.Path) -> tuple[np.ndarray, float]:
    """Reads a 16 kHz mono PCM file as float32 in [-1, 1]."""
    if not path.is_file():
        raise SystemExit(
            f"no audio at {path} — run make-bench-sample.ps1 to generate it")
    with wave.open(str(path), "rb") as f:
        if f.getnchannels() != 1 or f.getframerate() != 16000 or f.getsampwidth() != 2:
            raise SystemExit(
                f"{path}: expected 16 kHz mono 16-bit, got {f.getframerate()} Hz, "
                f"{f.getnchannels()} channel(s), {f.getsampwidth() * 8}-bit")
        frames = f.readframes(f.getnframes())
    samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    return samples, len(samples) / 16000.0


def main() -> int:
    here = pathlib.Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=str(here.parent / "models" / "whisper-large-v3-turbo-int8"))
    parser.add_argument("--audio", default=str(here / "bench-sample.wav"))
    parser.add_argument("--device", default="NPU")
    parser.add_argument("--cache", default=str(here.parent / ".ov_cache"))
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--language", default="<|en|>")
    parser.add_argument("--cold", action="store_true",
                        help="clear the blob cache first, to time a true cold compile")
    args = parser.parse_args()

    import openvino_genai

    model = pathlib.Path(args.model)
    if not model.is_dir():
        raise SystemExit(f"no exported model at {model} — run export-whisper.py first")

    audio, seconds = read_wav(pathlib.Path(args.audio))
    # Per device: a shared cache makes measuring the fallback ladder destroy the
    # blob of whichever device was measured first.
    cache = pathlib.Path(args.cache) / args.device
    cache.mkdir(parents=True, exist_ok=True)
    if args.cold:
        for blob in cache.iterdir():
            if blob.is_file():
                blob.unlink()

    print(f"model      : {model.name}")
    print(f"device     : {args.device}")
    print(f"audio      : {seconds:.2f} s")
    print(f"cache      : {cache}{' (cleared)' if args.cold else ''}")

    started = time.perf_counter()
    try:
        pipeline = openvino_genai.WhisperPipeline(str(model), device=args.device,
                                                  CACHE_DIR=str(cache))
    except Exception as e:
        # A compile failure is the answer to RISK-1, not an accident: report it as
        # the finding it is rather than as a stack trace.
        print(f"\nCOMPILE FAILED on {args.device}: {type(e).__name__}: {e}")
        return 2
    compile_s = time.perf_counter() - started
    print(f"compile    : {compile_s:.1f} s")

    config = pipeline.get_generation_config()
    config.language = args.language
    config.task = "transcribe"

    timings = []
    text = ""
    for i in range(args.runs):
        started = time.perf_counter()
        result = pipeline.generate(audio, config)
        timings.append((time.perf_counter() - started) * 1000.0)
        text = str(result)
        print(f"  run {i + 1}: {timings[-1]:7.1f} ms")

    p50 = statistics.median(timings)
    print(f"\ntranscript : {text.strip()[:120]}")
    print(f"recognition: p50 {p50:.0f} ms · min {min(timings):.0f} · max {max(timings):.0f}")
    print(f"threshold  : 550 ms  ->  {'PASS' if p50 <= 550 else 'FAIL'}")
    # Said out loud next to the number, because a figure quoted without it reads
    # as the production path and is not: the language is forced, so this run pays
    # neither language detection nor Russian's heavier tokenisation.
    print(f"measured with language={args.language} forced, task=transcribe")
    return 0 if p50 <= 550 else 1


if __name__ == "__main__":
    raise SystemExit(main())
