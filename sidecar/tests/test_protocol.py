"""The real sidecar's side of the JSON-lines protocol.

The Java contract test in aura-ipc pins this against `stub.py`. These check the
same contract against the implementation that will actually run, plus the two
things the stub cannot have an opinion about: what happens when a model is
missing, and what `speak: false` means.
"""

import io
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech import protocol  # noqa: E402
from aura_speech.events import to_narrator_input  # noqa: E402


def run(commands, narrate=lambda events, profile: "a line", speak=None):
    """Drives the loop over a canned command list and returns the events it emitted."""
    stdin = io.StringIO("".join(json.dumps(c) + "\n" for c in commands))
    stdout = io.StringIO()
    protocol.serve(stdin, stdout, narrate=narrate, speak=speak)
    return [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]


def kinds(events):
    return [e["ev"] for e in events]


def test_announces_itself_ready_before_anything_is_loaded():
    # The models take tens of seconds to compile. Announcing readiness only
    # afterwards would leave the tray showing a dead sidecar for a minute.
    events = run([])

    assert kinds(events) == ["ready"]
    assert events[0]["models"]["slm"] == "lazy"


def test_narration_is_emitted_before_speech_is_attempted():
    events = run([{"id": "c4", "cmd": "narrate", "events": [{"summaryHint": "ran pytest"}]}])

    assert kinds(events)[:2] == ["ready", "narration"]
    assert events[1]["text"] == "a line"
    assert events[1]["for"] == "c4"


def test_narration_without_speech_says_nothing_aloud():
    # Not pinned by the Java test, so it is pinned here: a text-only narration
    # emits the line and no speech events at all.
    events = run([{"id": "c4", "cmd": "narrate", "speak": False,
                   "events": [{"summaryHint": "ran pytest"}]}])

    assert kinds(events) == ["ready", "narration"]


def test_an_empty_window_produces_no_narration_event():
    events = run([{"id": "c4", "cmd": "narrate", "events": []}])

    assert kinds(events) == ["ready"]


def test_speech_that_cannot_play_reports_it_instead_of_pretending():
    # A sidecar that accepts speak and plays nothing is worse than one that
    # admits it cannot: the tray would show narration working while the user
    # hears silence.
    events = run([{"id": "c2", "cmd": "speak", "text": "hello"}])

    assert kinds(events) == ["ready", "error"]
    assert events[1]["code"] == "SPEECH_UNAVAILABLE"
    assert events[1]["fatal"] is False


def test_speech_is_started_and_finished_when_a_voice_is_present():
    spoken = []
    events = run([{"id": "c2", "cmd": "speak", "text": "hello"}],
                 speak=spoken.append)

    assert kinds(events) == ["ready", "speak.started", "speak.done"]
    assert spoken == ["hello"]
    assert events[1]["for"] == "c2"


def test_a_failing_model_reports_an_error_rather_than_killing_the_sidecar():
    def explode(events, profile):
        raise RuntimeError("model went away")

    events = run([{"id": "c4", "cmd": "narrate", "events": [{"summaryHint": "x"}]},
                  {"id": "c5", "cmd": "narrate", "events": [{"summaryHint": "y"}]}],
                 narrate=explode)

    # Two failures, both reported, and the loop still reading afterwards.
    assert kinds(events) == ["ready", "error", "error"]
    assert all(e["code"] == "NARRATION_FAILED" for e in events[1:])
    assert all(e["fatal"] is False for e in events[1:])


def test_unknown_command_is_an_error_not_silence():
    events = run([{"id": "cX", "cmd": "fly_to_the_moon"}])

    assert kinds(events) == ["ready", "error"]
    assert events[1]["code"] == "UNKNOWN_COMMAND"


def test_malformed_json_does_not_end_the_conversation():
    stdin = io.StringIO('not json\n{"id":"c1","cmd":"configure","profile":"ru"}\n')
    stdout = io.StringIO()
    protocol.serve(stdin, stdout, narrate=lambda e, p: "x")
    events = [json.loads(line) for line in stdout.getvalue().splitlines()]

    assert kinds(events) == ["ready", "error"]
    assert events[1]["code"] == "BAD_JSON"


def test_configure_selects_the_profile_the_narrator_is_given():
    seen = []

    def narrate(events, profile):
        seen.append(profile)
        return "x"

    run([{"id": "c1", "cmd": "configure", "profile": "ru"},
         {"id": "c4", "cmd": "narrate", "events": [{"summaryHint": "x"}]}],
        narrate=narrate)

    assert seen == ["ru"]


def test_shutdown_stops_reading():
    events = run([{"id": "c10", "cmd": "shutdown"},
                  {"id": "c11", "cmd": "fly_to_the_moon"}])

    assert kinds(events) == ["ready"]


class TestEventMapping:
    """AgentEvent, as the Java side serialises it, into lines a narrator can read."""

    def test_summary_hint_is_preferred(self):
        assert to_narrator_input([{"kind": "TOOL_END", "target": "pytest",
                                   "summaryHint": "3 passed, 1 failed"}]) == \
            ["3 passed, 1 failed"]

    def test_events_without_a_hint_fall_back_to_kind_and_target(self):
        assert to_narrator_input([{"kind": "TOOL_START", "target": "pytest"}]) == \
            ["TOOL_START pytest"]

    def test_events_with_neither_are_dropped_rather_than_narrated_as_blanks(self):
        assert to_narrator_input([{"kind": "", "target": ""}, {"summaryHint": "  "}]) == []

    def test_order_is_preserved(self):
        assert to_narrator_input([{"summaryHint": "first"}, {"summaryHint": "second"}]) == \
            ["first", "second"]
