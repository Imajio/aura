"""What the narrator must never say out loud, and what it must say instead."""

import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.narrator import Narrator  # noqa: E402


def narrator(answer: str, profile: str = "en") -> tuple[Narrator, list]:
    """A narrator wired to a fake model that always answers the same thing."""
    prompts = []

    def generate(prompt: str) -> str:
        prompts.append(prompt)
        return answer

    return Narrator(generate, profile=profile), prompts


def test_reasoning_block_is_never_spoken():
    # Measured on Qwen3: it emits a think block whether or not it is asked to.
    # Spoken aloud, the user hears the model deliberating about them.
    speaker, _ = narrator("<think>\nThe user ran tests. Let me summarise.\n</think>\n\n"
                          "Three tests passed and one failed.")

    assert speaker.line(["ran pytest", "3 passed, 1 failed"]) == \
        "Three tests passed and one failed."


def test_empty_reasoning_block_is_stripped_too():
    # This is the shape /no_think produces, and it arrives on every single call.
    speaker, _ = narrator("<think>\n\n</think>\n\nTests are running.")

    assert speaker.line(["ran pytest"]) == "Tests are running."


def test_unclosed_reasoning_block_yields_nothing_rather_than_thoughts():
    # A truncated answer — the token ceiling cut the model off mid-thought.
    # Saying the fragment aloud is worse than staying quiet.
    speaker, _ = narrator("<think>\nLet me think about what to say about")

    assert speaker.line(["ran pytest"]) == ""


def test_markdown_and_paths_do_not_reach_speech():
    speaker, _ = narrator("**Fixed** `src/main/java/aura/App.java` — see the *diff*.")

    assert speaker.line(["edited a file"]) == "Fixed src/main/java/aura/App.java — see the diff."


def test_only_the_first_sentence_is_spoken():
    # The design allows one or two phrases; the model does not always agree.
    speaker, _ = narrator("Tests are running. I will report when they finish. "
                          "This may take a while.")

    assert speaker.line(["ran pytest"]) == "Tests are running."


def test_no_events_means_no_model_call_and_nothing_said():
    speaker, prompts = narrator("something")

    assert speaker.line([]) == ""
    assert prompts == []


def test_events_reach_the_prompt_in_order():
    speaker, prompts = narrator("ok")
    speaker.line(["started task", "ran pytest", "3 passed"])

    assert len(prompts) == 1
    assert prompts[0].index("started task") < prompts[0].index("ran pytest")
    assert "3 passed" in prompts[0]


def test_prompt_suppresses_reasoning():
    # Not cosmetic: with reasoning left on, the first sentence lands at 2.5 s
    # against a 900 ms budget for the whole spoken path.
    speaker, prompts = narrator("ok")
    speaker.line(["ran pytest"])

    assert "/no_think" in prompts[0]


def test_russian_profile_asks_for_russian():
    speaker, prompts = narrator("готово", profile="ru")
    speaker.line(["запущен pytest"])

    assert "русск" in prompts[0].lower()


def test_unknown_profile_falls_back_to_english_rather_than_failing():
    # A profile typo must not silence the narrator: saying something in the
    # wrong language beats saying nothing about a failing build.
    speaker, prompts = narrator("ok", profile="klingon")

    assert speaker.line(["ran pytest"]) == "ok"
    assert prompts
