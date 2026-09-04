"""Checks the narrator's guarantees against the real model, when one is present.

The unit tests next door use a fake that answers whatever they ask it to, which
proves the guards work on the shapes we know about. This proves the shapes are
the ones the model actually produces — the reason the think-block guard exists
at all is that Qwen3 emits it unbidden, and a fake cannot notice when that
stops being true.

Skipped, not failed, when the model is not on this machine: it is two gigabytes
and lives outside the repository, so its absence is the normal case everywhere
except the machine the project is developed on.
"""

import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.narrator import Narrator  # noqa: E402

MODEL = pathlib.Path(__file__).resolve().parents[2] / "models" / "qwen3-4b-int4-ov"
CACHE = pathlib.Path(__file__).resolve().parents[2] / ".ov_cache" / "GPU-narrator"

pytestmark = pytest.mark.skipif(not MODEL.is_dir(),
                                reason=f"no narrator model at {MODEL}")


@pytest.fixture(scope="module")
def generate():
    genai = pytest.importorskip("openvino_genai")
    pipeline = genai.LLMPipeline(str(MODEL), device="GPU", CACHE_DIR=str(CACHE))
    config = pipeline.get_generation_config()
    config.max_new_tokens = 40
    config.do_sample = False
    return lambda prompt: str(pipeline.generate(prompt, config))


@pytest.mark.parametrize("profile,events", [
    ("en", ["started task in project backend", "ran pytest", "3 passed, 1 failed"]),
    ("ru", ["начата задача в проекте бэкенд", "запущен pytest", "3 passed, 1 failed"]),
])
def test_the_model_produces_something_speakable(generate, profile, events):
    line = Narrator(generate, profile=profile).line(events)

    assert line, "the narrator said nothing about a window that had events in it"
    # None of this may reach a synthesiser: it would be read out as punctuation,
    # or as the model's own deliberation.
    assert "<think>" not in line
    assert "</think>" not in line
    assert not any(c in line for c in "*_`#")
    assert line.rstrip()[-1] in ".!?…", f"not a finished sentence: {line!r}"
    assert len(line.split()) <= 25, f"too long to be one spoken line: {line!r}"
