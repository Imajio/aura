"""Measures how long the narrator takes to say its first word.

This is the experiment behind RISK-7. The design budgets **900 ms from event to
first spoken word**, and the sidecar generates and speaks sentence by sentence
rather than waiting for the whole answer — so the number that matters is not
total generation time but the time until the first sentence exists.

Three timings, and the budget belongs to all of them together:

* **compile** — building the pipeline for the device, paid once and then served
  from the blob cache.
* **TTFT** — event in, first token out. The model's own latency floor.
* **first sentence** — until there is something a synthesiser can start on.
  This, plus whatever TTS costs, is what has to fit inside 900 ms. The
  measurement here covers the first two of three legs; a pass on this script is
  necessary for the budget, not sufficient for it.

Both language profiles are measured. Russian is not a translation of the same
work: it costs more tokens per sentence, and the narrator is the one component
where that lands directly on the number the user perceives.

    .venv-export/Scripts/python.exe benchmark-narrator.py --device GPU

Exit codes: 0 first sentence within budget, 1 over it, 2 would not compile.
"""

import argparse
import pathlib
import statistics
import time

SYSTEM = {
    "en": (
        "You narrate a coding agent's progress out loud. One short spoken "
        "sentence, at most fifteen words. No file paths, no markdown, no "
        "identifiers longer than three words. Plain speech, nothing else."
    ),
    "ru": (
        "Ты озвучиваешь ход работы кодового агента. Одна короткая устная фраза, "
        "не длиннее пятнадцати слов. Без путей к файлам, без markdown, без "
        "идентификаторов длиннее трёх слов. Только разговорная речь."
    ),
}

# A real accumulation window: what the adapters actually hand the narrator after
# a few events, not a toy prompt that flatters the measurement.
EVENTS = {
    "en": ("Events: started task in project backend; ran pytest; "
           "3 passed, 1 failed; failing test is token expiry check."),
    "ru": ("События: начата задача в проекте бэкенд; запущен pytest; "
           "3 passed, 1 failed; упавший тест — проверка срока токена."),
}


def main() -> int:
    here = pathlib.Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=str(here.parent / "models" / "qwen3-4b-int4-ov"))
    parser.add_argument("--device", default="GPU")
    parser.add_argument("--cache", default=str(here.parent / ".ov_cache"))
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--max-tokens", type=int, default=40)
    parser.add_argument("--budget-ms", type=float, default=900.0)
    parser.add_argument("--think", action="store_true",
                        help="leave Qwen3 reasoning on; off by default, "
                             "because the narrator has nothing to reason about")
    args = parser.parse_args()

    import openvino_genai

    model = pathlib.Path(args.model)
    if not model.is_dir():
        raise SystemExit(f"no model at {model}")

    cache = pathlib.Path(args.cache) / f"{args.device}-narrator"
    cache.mkdir(parents=True, exist_ok=True)

    print(f"model      : {model.name}")
    print(f"device     : {args.device}")

    started = time.perf_counter()
    try:
        pipeline = openvino_genai.LLMPipeline(str(model), device=args.device,
                                              CACHE_DIR=str(cache))
    except Exception as e:
        print(f"\nCOMPILE FAILED on {args.device}: {type(e).__name__}: {e}")
        return 2
    print(f"compile    : {time.perf_counter() - started:.1f} s")

    config = pipeline.get_generation_config()
    config.max_new_tokens = args.max_tokens
    config.do_sample = False

    def build(lang: str) -> str:
        # Qwen3 is a reasoning model and thinks out loud by default: left alone it
        # spends the entire budget on a monologue the user never hears — 2.5 s to
        # the first sentence, against 900 ms for the whole path.
        #
        # The template's `enable_thinking` hook is not usable here: LLMPipeline
        # applies its own chat template to whatever string it is handed, so a
        # pre-templated prompt is templated again and the prefill is buried. The
        # `/no_think` marker travels inside the message text and survives that.
        marker = "" if args.think else " /no_think"
        return f"{SYSTEM[lang]}{marker}\n\n{EVENTS[lang]}"

    worst = 0.0
    for lang in ("en", "ru"):
        prompt = build(lang)
        ttfts, sentences = [], []
        text = ""

        for _ in range(args.runs):
            marks = {"first": None, "sentence": None}
            collected = []

            def streamer(chunk: str) -> bool:
                now = time.perf_counter()
                collected.append(chunk)
                sofar = "".join(collected)
                # Everything up to </think> is not speech, even when the block is
                # empty. Timing the first token of it would report a latency the
                # user never experiences.
                spoken = sofar.split("</think>", 1)[-1] if "</think>" in sofar else (
                    "" if "<think>" in sofar else sofar)
                if marks["first"] is None and spoken.strip():
                    marks["first"] = now
                # First sentence: the point a synthesiser could already start.
                if marks["sentence"] is None and any(c in spoken for c in ".!?…"):
                    marks["sentence"] = now
                return False  # keep generating

            begin = time.perf_counter()
            pipeline.generate(prompt, config, streamer)
            end = time.perf_counter()

            first = marks["first"] or end
            sentence = marks["sentence"] or end
            ttfts.append((first - begin) * 1000.0)
            sentences.append((sentence - begin) * 1000.0)
            text = "".join(collected)
            # Printed per run, not just as a median: the first call after a
            # compile costs several times the rest, and a median hides whether
            # that is what you are looking at.
            print(f"  [{lang}] run {len(ttfts)}: TTFT {ttfts[-1]:6.0f} ms · "
                  f"first sentence {sentences[-1]:6.0f} ms")

        ttft = statistics.median(ttfts)
        first_sentence = statistics.median(sentences)
        worst = max(worst, first_sentence)
        print(f"\n[{lang}] {text.strip()[:110]}")
        print(f"[{lang}] TTFT p50 {ttft:.0f} ms · first sentence p50 {first_sentence:.0f} ms")

    print(f"\nbudget     : {args.budget_ms:.0f} ms event -> first word, "
          f"synthesis still to come out of it")
    print(f"worst first sentence: {worst:.0f} ms  ->  "
          f"{'PASS' if worst <= args.budget_ms else 'FAIL'}")
    return 0 if worst <= args.budget_ms else 1


if __name__ == "__main__":
    raise SystemExit(main())
