"""Measures what unloading a model actually costs the next person to speak.

This is RISK-9, and its original framing has expired. It asked what switching
compiled blobs costs *on the NPU*. RISK-1 then established that recognition
does not run on the NPU at all, so both the recogniser and the narrator now
live on the same iGPU — which makes the real question sharper and more
expensive to get wrong:

    the model registry unloads STT after 60 s idle and the narrator after 120 s.
    What does the user pay for that, the next time they speak?

Three costs, and only the first is the one people think of:

* **load** — building the pipeline again from the blob cache.
* **first inference** — measured separately, because it is not free and not
  small: Whisper costs several seconds on its first call after a load and
  roughly 300 ms on every call after that.
* **steady state** — what the design's latency budgets were written against.

Two regimes are compared. *Resident* keeps both models alive and alternates
between them, which is what happens inside the idle window. *Reloaded* drops
each model and builds it again before every use, which is what happens outside
it. The difference between them is the true price of the unload policy.

    .venv-export/Scripts/python.exe benchmark-model-switching.py --device GPU

Exit code is 0 whenever the measurement completed: this script reports a cost,
it does not judge a threshold — the number feeds a policy decision that is not
the script's to make.
"""

import argparse
import gc
import pathlib
import statistics
import time
import wave

import numpy as np

NARRATOR_PROMPT = (
    "You narrate a coding agent's progress out loud. One short spoken "
    "sentence. /no_think\n\nEvents: ran pytest; 3 passed, 1 failed."
)


def read_wav(path: pathlib.Path) -> np.ndarray:
    with wave.open(str(path), "rb") as f:
        frames = f.readframes(f.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def main() -> int:
    here = pathlib.Path(__file__).parent
    models = here.parent / "models"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stt", default=str(models / "whisper-large-v3-turbo-int8"))
    parser.add_argument("--slm", default=str(models / "qwen3-1.7b-int4-ov"))
    parser.add_argument("--audio", default=str(here / "bench-sample.wav"))
    parser.add_argument("--device", default="GPU")
    parser.add_argument("--cache", default=str(here.parent / ".ov_cache"))
    parser.add_argument("--cycles", type=int, default=3)
    args = parser.parse_args()

    import openvino_genai

    audio = read_wav(pathlib.Path(args.audio))
    cache = pathlib.Path(args.cache) / f"{args.device}-switching"
    cache.mkdir(parents=True, exist_ok=True)

    def load_stt():
        return openvino_genai.WhisperPipeline(args.stt, device=args.device,
                                              CACHE_DIR=str(cache))

    def load_slm():
        return openvino_genai.LLMPipeline(args.slm, device=args.device,
                                          CACHE_DIR=str(cache))

    def run_stt(pipe) -> None:
        config = pipe.get_generation_config()
        config.language = "<|en|>"
        config.task = "transcribe"
        pipe.generate(audio, config)

    def run_slm(pipe) -> None:
        config = pipe.get_generation_config()
        config.max_new_tokens = 40
        config.do_sample = False
        pipe.generate(NARRATOR_PROMPT, config)

    stages = (("stt", load_stt, run_stt), ("slm", load_slm, run_slm))

    print(f"device     : {args.device}")
    print(f"cache      : {cache}")
    print(f"cycles     : {args.cycles}\n")

    # Warm the cache first, so "load" means loading a compiled blob and not
    # compiling one. The cold compile is a different number, measured elsewhere.
    for name, load, run in stages:
        pipe = load()
        run(pipe)
        del pipe
    gc.collect()

    print("resident — both models alive, alternating (inside the idle window)")
    resident = {name: [] for name, _, _ in stages}
    pipes = {name: load() for name, load, _ in stages}
    for name, _, run in stages:
        run(pipes[name])  # first call after load, kept out of the numbers below
    for _ in range(args.cycles):
        for name, _, run in stages:
            started = time.perf_counter()
            run(pipes[name])
            resident[name].append((time.perf_counter() - started) * 1000.0)
    for name in resident:
        print(f"  {name}: p50 {statistics.median(resident[name]):7.0f} ms")
    del pipes
    gc.collect()

    print("\nreloaded — dropped and rebuilt before every use (outside it)")
    loads = {name: [] for name, _, _ in stages}
    firsts = {name: [] for name, _, _ in stages}
    for _ in range(args.cycles):
        for name, load, run in stages:
            started = time.perf_counter()
            pipe = load()
            loaded = time.perf_counter()
            run(pipe)
            done = time.perf_counter()
            loads[name].append((loaded - started) * 1000.0)
            firsts[name].append((done - loaded) * 1000.0)
            del pipe
            gc.collect()
    for name in loads:
        load_p50 = statistics.median(loads[name])
        first_p50 = statistics.median(firsts[name])
        steady = statistics.median(resident[name])
        print(f"  {name}: load {load_p50:7.0f} ms + first inference {first_p50:7.0f} ms"
              f"  =  {load_p50 + first_p50:7.0f} ms")
        print(f"       against {steady:.0f} ms resident — "
              f"{(load_p50 + first_p50) / steady:.1f}x")

    print("\nThe multiplier is what the unload policy charges the user for the "
          "first utterance\nafter an idle period. It is a policy input, not a "
          "verdict.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
