"""Turns a window of agent events into one line the user hears.

The narrator exists so that watching an agent work costs no tokens: instead of
asking the coding agent to summarise itself, a local model reads the event
stream and says one short thing about it. That makes its output a user
interface, not a log line, and two properties matter more than eloquence.

**It must not think out loud.** Qwen3 is a reasoning model: it deliberates
inside a `<think>` block before answering, and it emits that block whether or
not it was asked to. Spoken aloud, the user hears the model reasoning about
them. The block is suppressed in the prompt and stripped from the answer, and
neither measure is trusted alone.

**It must not read punctuation aloud.** Markdown, file paths and identifiers
are written to be seen. A synthesiser given `**Fixed** \\`src/main/App.java\\``
pronounces the asterisks and the backticks.
"""

import re

SYSTEM = {
    "en": ("You narrate a coding agent's progress out loud. One short spoken "
           "sentence, at most fifteen words. No file paths, no markdown, no "
           "identifiers longer than three words. Plain speech, nothing else."),
    "ru": ("Ты озвучиваешь ход работы кодового агента. Отвечай по-русски. Одна "
           "короткая устная фраза, не длиннее пятнадцати слов. Без путей к "
           "файлам, без markdown, без идентификаторов длиннее трёх слов. "
           "Только разговорная речь."),
}

DEFAULT_PROFILE = "en"

# Qwen3 honours this marker inside the message text. The chat template's own
# `enable_thinking` hook does not survive the pipeline, which re-applies its
# template to whatever string it is given and buries the prefill.
NO_THINK = "/no_think"

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL)
_MARKDOWN = re.compile(r"[*_`#]+")
# A terminator only ends a sentence when something stops after it. Without the
# lookahead the dot in "App.java" ends the sentence and the narration is cut in
# half - which is exactly what happens the first time a model mentions a file
# despite being told not to.
_SENTENCE_END = re.compile(r"[.!?…](?=\s|$)")


class Narrator:
    """Builds the prompt, and guards what comes back."""

    def __init__(self, generate, profile: str = DEFAULT_PROFILE):
        """`generate` takes a prompt and returns the model's raw answer.

        Injected rather than constructed here so the guarantees below can be
        tested without a two-gigabyte model, and so the fallback to a smaller
        model on battery is a decision made outside this class.
        """
        self._generate = generate
        self._profile = profile if profile in SYSTEM else DEFAULT_PROFILE

    def line(self, events: list[str]) -> str:
        """One spoken sentence about these events, or "" if there is nothing to say."""
        if not events:
            # No call at all: an empty window is silence, not a prompt asking
            # the model to invent something.
            return ""
        return self._spoken(self._generate(self._prompt(events)))

    def _prompt(self, events: list[str]) -> str:
        system = SYSTEM[self._profile]
        return f"{system} {NO_THINK}\n\nEvents: " + "; ".join(events)

    @staticmethod
    def _spoken(answer: str) -> str:
        if not answer:
            return ""

        text = _THINK.sub("", answer)
        if "<think>" in text:
            # The block never closed: the token ceiling cut the model off while
            # it was still deliberating. Everything present is reasoning, and a
            # truncated thought spoken aloud is worse than silence.
            return ""

        text = _MARKDOWN.sub("", text).strip()

        # One sentence. The design allows a second, but the model does not
        # reliably stop, and a narrator that keeps talking is the complaint the
        # verbosity control exists to prevent.
        match = _SENTENCE_END.search(text)
        if match:
            text = text[:match.end()]
        return text.strip()
