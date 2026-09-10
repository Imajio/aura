"""voice.status, record, enrol and train.wake, over the same protocol.

Every worker-backed command answers from a thread, not from the loop that reads
stdin — `serve` has to stay free to answer `narrate` and `speak.cancel` while a
hundred clips are being embedded. `io.StringIO` does not block like a real pipe
would, so `serve` here returns long before a worker thread is done; every test
that starts one waits for its own terminal event with `wait_for` rather than
reading `stdout` the instant `serve` returns.

`VoiceResources` is the plain object `serve`'s new `voice` parameter expects:
paths, an optional listening handle, optional factories that stand in for real
models, and the one busy flag that makes the three worker commands mutually
exclusive. The sequencing itself — pause before recording, resume after; one
worker at a time; a `ValueError` from training becoming an `error` event — lives
in `protocol.py`'s `_record`/`_enrol`/`_train_wake`, so it is exercised here
directly rather than trusted to untested wiring in main.py.
"""

import io
import json
import pathlib
import sys
import threading
import time

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech import protocol, training          # noqa: E402
from aura_speech.protocol import VoiceResources      # noqa: E402
from aura_speech.recording import take_path, write_take  # noqa: E402


def a_take(directory, kind, number, level=0.2):
    rng = np.random.default_rng(number)
    write_take(take_path(directory, kind, number),
               (rng.standard_normal(16000) * level).astype(np.float32))


def a_voice(tmp_path, **overrides):
    kwargs = dict(
        reference_dir=tmp_path / "voice" / "reference",
        wake_dir=tmp_path / "voice" / "wake",
        reference_path=tmp_path / "voice" / "reference.npy",
        wake_model_path=tmp_path / "voice" / "wake-word.npz",
        speaker_model_path=tmp_path / "models" / "speaker.onnx",
        feature_models_dir=tmp_path / "models" / "openwakeword",
        negative_dir=tmp_path / "negative",
    )
    kwargs.update(overrides)
    return VoiceResources(**kwargs)


def run(commands, voice=None, narrate=lambda events, profile, verbosity: "a line",
       speak=None, cancel=None):
    """Drives `serve` over a canned command list. Returns the live stdout buffer,
    not the parsed events: a worker-backed command is still running when `serve`
    returns, so callers wait on the buffer with `wait_for` before reading it."""
    stdin = io.StringIO("".join(json.dumps(c) + "\n" for c in commands))
    stdout = io.StringIO()
    protocol.serve(stdin, stdout, narrate=narrate, speak=speak, cancel=cancel, voice=voice)
    return stdout


def events_of(stdout):
    return [json.loads(line) for line in stdout.getvalue().splitlines() if line.strip()]


def kinds(events):
    return [e["ev"] for e in events]


def wait_for(stdout, predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        events = events_of(stdout)
        if predicate(events):
            return events
        time.sleep(0.005)
    raise AssertionError(f"condition not met within {timeout}s; got {events_of(stdout)}")


def until_terminal(stdout, timeout=5.0):
    """Waits for whichever event ends a worker-backed command's story."""
    return wait_for(stdout, lambda events: kinds(events)[-1:] in
                    (["record.done"], ["train.done"], ["error"]), timeout)


class FakeListening:
    """Stands in for Task 2's Listening, including the part that is easy to get wrong.

    `active()` is `wanted and not closed`, and `pause()` clears `wanted`
    **before** it reports whether the device actually closed — both exactly as
    `listening.py` does them. That distinction is the whole subsystem: a pause
    that times out has already stopped wanting the microphone while the stream
    is still open, so a fake that went inactive only on a *successful* pause
    would report `listening: true` in the one case this code exists to catch,
    and a mutation dropping the check would sail past green.
    """

    def __init__(self, log=None, pause_returns=True, active=True):
        self.calls = log if log is not None else []
        self.pause_returns = pause_returns
        self._wanted = active
        self._closed = False

    def start(self):
        self.calls.append("start")
        self._wanted = True

    def pause(self, timeout=5.0):
        self.calls.append("pause")
        self._wanted = False
        return self.pause_returns

    def resume(self):
        self.calls.append("resume")
        self._wanted = True

    def close(self):
        self._closed = True
        self._wanted = False

    def active(self):
        return self._wanted and not self._closed


def fake_record_take(log, level=0.05):
    def record_take(seconds, device_index):
        log.append("record")
        return np.full(int(seconds * 16000), level, dtype=np.float32)
    return record_take


class _FakeSession:
    """Stands in for onnxruntime.InferenceSession: only the shape wake_features reads."""

    def __init__(self, output):
        self._output = output

    def run(self, output_names, inputs):
        return [self._output]


def fake_sessions_factory(_directory):
    # 80 frames -> exactly one 76-frame window regardless of clip length, since
    # the fake ignores its input; one window of 96 numbers is all
    # train_wake_word needs from either session to run for real.
    return (_FakeSession(np.zeros((1, 80, 32), dtype=np.float32)),
           _FakeSession(np.ones((1, 96), dtype=np.float32)))


def test_voice_status_reports_each_artefact_and_the_listening_state(tmp_path):
    # Breaks if protocol.py stops calling voice.status(), drops a field, or
    # forgets the "for" correlation id.
    a_take(tmp_path / "voice" / "reference", "reference", 1)
    a_take(tmp_path / "voice" / "reference", "reference", 2)
    for n in (1, 2, 3):
        a_take(tmp_path / "voice" / "wake", "wake", n)
    reference_path = tmp_path / "voice" / "reference.npy"
    reference_path.parent.mkdir(parents=True, exist_ok=True)
    reference_path.write_bytes(b"x")
    wake_model_path = tmp_path / "voice" / "wake-word.npz"
    wake_model_path.parent.mkdir(parents=True, exist_ok=True)
    wake_model_path.write_bytes(b"x")
    speaker_model = tmp_path / "models" / "speaker.onnx"
    speaker_model.parent.mkdir(parents=True, exist_ok=True)
    speaker_model.write_bytes(b"x")
    voice = a_voice(tmp_path, listening=FakeListening(active=True))

    events = events_of(run([{"id": "s1", "cmd": "voice.status"}], voice=voice))

    assert kinds(events) == ["ready", "voice.status"]
    status = events[1]
    assert status["for"] == "s1"
    assert status["referenceTakes"] == 2
    assert status["wakeTakes"] == 3
    assert status["reference"] is True
    assert status["wakeModel"] is True
    assert status["speakerModel"] is True
    assert status["listening"] is True
    assert status["featureModels"] is False
    assert status["negatives"] == 0


def test_voice_status_reports_the_two_things_training_needs_besides_takes(tmp_path):
    # Breaks if status() stops reporting either one. train_wake_word refuses
    # without openWakeWord's two ONNX files and without audio that is not the
    # wake word, and a client that cannot see them has to offer a button that
    # fails — which is the one thing the window is built not to do. The negative
    # count is recursive because training.py collects them with rglob: the
    # audition samples sit in one folder per voice.
    for name in training.FEATURE_MODELS:
        model = tmp_path / "models" / "openwakeword" / name
        model.parent.mkdir(parents=True, exist_ok=True)
        model.write_bytes(b"x")
    for voice_name in ("v4", "v5"):
        clip = tmp_path / "negative" / voice_name / "line.wav"
        clip.parent.mkdir(parents=True, exist_ok=True)
        clip.write_bytes(b"x")
    voice = a_voice(tmp_path)

    status = events_of(run([{"id": "s2", "cmd": "voice.status"}], voice=voice))[1]

    assert status["featureModels"] is True
    assert status["negatives"] == 2


def test_voice_status_reports_feature_models_missing_when_only_one_is_there(tmp_path):
    # Breaks if the check is any() rather than all(). One of the two files is
    # useless on its own: training loads both and raises on whichever is absent.
    model = tmp_path / "models" / "openwakeword" / training.FEATURE_MODELS[0]
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_bytes(b"x")
    voice = a_voice(tmp_path)

    status = events_of(run([{"id": "s3", "cmd": "voice.status"}], voice=voice))[1]

    assert status["featureModels"] is False


def test_record_emits_started_then_one_take_per_recording_then_done(tmp_path):
    # Breaks if _record stops calling voice.record_take per take, emits the
    # events out of order, or emits record.done before every take is written.
    log = []
    voice = a_voice(tmp_path, record_take=fake_record_take(log))

    stdout = run([{"id": "r1", "cmd": "record", "kind": "reference", "takes": 2}], voice=voice)
    events = until_terminal(stdout)

    assert kinds(events) == ["ready", "record.started", "record.take", "record.take",
                             "record.done"]
    assert log.count("record") == 2
    takes = [e for e in events if e["ev"] == "record.take"]
    assert [t["number"] for t in takes] == [1, 2]
    assert all(t["path"] for t in takes)
    assert all(e["for"] == "r1" for e in events[1:])
    assert len(list((tmp_path / "voice" / "reference").glob("*.wav"))) == 2


def test_record_pauses_listening_before_recording_and_resumes_after(tmp_path):
    # Breaks if _record pauses after recording instead of before, skips resume,
    # or resumes before every take is written. Assert on the call log's order,
    # not on timing, since both fakes share it.
    log = []
    listening = FakeListening(log=log)
    voice = a_voice(tmp_path, listening=listening, record_take=fake_record_take(log))

    stdout = run([{"id": "r1", "cmd": "record", "kind": "wake", "takes": 2}], voice=voice)
    until_terminal(stdout)

    assert log == ["pause", "record", "record", "resume"]


def test_record_when_listening_will_not_release_reports_microphone_in_use(tmp_path):
    # Breaks if _record calls the recorder without checking pause()'s return,
    # or resumes listening that was never actually paused.
    log = []
    listening = FakeListening(log=log, pause_returns=False)
    voice = a_voice(tmp_path, listening=listening, record_take=fake_record_take(log))

    stdout = run([{"id": "r1", "cmd": "record", "kind": "reference", "takes": 1}], voice=voice)
    events = until_terminal(stdout)

    # record.started fires on acceptance, before the worker thread learns the
    # device would not release; the failure itself is the last event.
    assert kinds(events) == ["ready", "record.started", "error"]
    assert events[-1]["code"] == "MICROPHONE_IN_USE"
    assert events[-1]["for"] == "r1"
    assert "record" not in log
    assert log == ["pause"]


def test_record_while_listening_is_off_leaves_the_microphone_off(tmp_path):
    # Breaks if _record resumes unconditionally instead of only when listening
    # was running before it took the device. pause() on an idle Listening
    # returns True at once, so the unconditional version would switch the
    # microphone on at the end of a recording nobody asked to be heard after.
    log = []
    listening = FakeListening(log=log, active=False)
    voice = a_voice(tmp_path, listening=listening, record_take=fake_record_take(log))

    stdout = run([{"id": "r1", "cmd": "record", "kind": "wake", "takes": 1}], voice=voice)
    until_terminal(stdout)

    assert log == ["pause", "record"]
    assert listening.active() is False


def test_configure_listen_true_starts_listening_and_answers_with_a_status(tmp_path):
    # Breaks if configure ignores the listen field again, calls something other
    # than start(), or stays silent — the window's toggle reads its state from
    # the voice.status this emits, so no status means a toggle that shows the
    # click rather than the microphone.
    log = []
    listening = FakeListening(log=log, active=False)
    voice = a_voice(tmp_path, listening=listening)

    events = events_of(run([{"id": "l1", "cmd": "configure", "listen": True}], voice=voice))

    assert log == ["start"]
    assert kinds(events) == ["ready", "voice.status"]
    assert events[-1]["for"] == "l1"
    assert events[-1]["listening"] is True


def test_configure_listen_false_pauses_listening_and_answers_with_a_status(tmp_path):
    # Breaks if turning listening off stops at clearing a flag without waiting
    # for the device, or answers without a status.
    log = []
    listening = FakeListening(log=log, active=True)
    voice = a_voice(tmp_path, listening=listening)

    events = events_of(run([{"id": "l2", "cmd": "configure", "listen": False}], voice=voice))

    assert log == ["pause"]
    assert kinds(events) == ["ready", "voice.status"]
    assert events[-1]["listening"] is False


def test_configure_listen_false_that_cannot_release_the_device_says_listening_is_still_on(
        tmp_path):
    # The reason pause() has a return value at all. Breaks if _listen ignores it
    # and emits a status claiming the microphone is off while the stream is
    # still open — the owner would be told the room is private when it is not.
    log = []
    listening = FakeListening(log=log, pause_returns=False, active=True)
    voice = a_voice(tmp_path, listening=listening)

    events = events_of(run([{"id": "l3", "cmd": "configure", "listen": False}], voice=voice))

    assert log == ["pause", "start"]
    assert kinds(events) == ["ready", "error", "voice.status"]
    assert events[1]["code"] == "MICROPHONE_IN_USE"
    assert events[1]["for"] == "l3"
    assert events[-1]["listening"] is True


def test_configure_listen_without_a_listening_handle_says_so_and_still_answers(tmp_path):
    # Breaks if _listen assumes there is always a Listening to talk to: a
    # sidecar started without --listen has no capture thread, and an
    # AttributeError there would take the whole command loop down.
    voice = a_voice(tmp_path, listening=None)

    events = events_of(run([{"id": "l4", "cmd": "configure", "listen": True}], voice=voice))

    assert kinds(events) == ["ready", "error", "voice.status"]
    assert events[1]["code"] == "LISTENING_UNAVAILABLE"
    assert events[-1]["listening"] is False


def test_configure_listen_without_voice_support_answers_voice_unavailable():
    # Breaks if the listen branch is added without the same VOICE_UNAVAILABLE
    # guard the other voice commands have; `voice` is None in stub.py and in the
    # Java contract test, where a status could not be answered at all.
    events = events_of(run([{"id": "l5", "cmd": "configure", "listen": True}]))

    assert kinds(events) == ["ready", "error"]
    assert events[1]["code"] == "VOICE_UNAVAILABLE"
    assert events[1]["for"] == "l5"


def test_configure_without_a_listen_field_answers_nothing(tmp_path):
    # Breaks if the listen branch fires on every configure. The tray's narration
    # menu sends one on every level change, and a voice.status per menu click is
    # noise the window would have to learn to ignore.
    log = []
    voice = a_voice(tmp_path, listening=FakeListening(log=log))

    events = events_of(run([{"id": "c1", "cmd": "configure", "verbosity": "quiet"}],
                           voice=voice))

    assert kinds(events) == ["ready"]
    assert log == []


def test_a_second_command_while_one_is_running_gets_busy_and_the_first_finishes(tmp_path):
    # Breaks if the busy gate is removed or checked after work has already
    # started: the second command would then be served (or silently dropped)
    # instead of answered with BUSY, and/or the first would be disturbed.
    a_take(tmp_path / "voice" / "reference", "reference", 1)
    speaker_model = tmp_path / "models" / "speaker.onnx"
    speaker_model.parent.mkdir(parents=True, exist_ok=True)
    speaker_model.write_bytes(b"x")
    gate = threading.Event()

    def slow_embed_factory(_model_path):
        def embed(_audio):
            gate.wait(2.0)
            return np.ones(4, dtype=np.float32)
        return embed

    voice = a_voice(tmp_path, embed_factory=slow_embed_factory)

    stdout = run([{"id": "e1", "cmd": "enrol"},
                  {"id": "r1", "cmd": "record", "kind": "wake", "takes": 1}], voice=voice)
    events = wait_for(stdout, lambda events: any(e.get("for") == "r1" for e in events))

    rejected = [e for e in events if e.get("for") == "r1"]
    assert len(rejected) == 1
    assert rejected[0]["ev"] == "error"
    assert rejected[0]["code"] == "BUSY"

    # until_terminal would be ambiguous here: r1's own BUSY error already
    # satisfies "the last event is terminal" before e1's worker even wakes up.
    # Wait for e1's specific outcome instead.
    gate.set()
    events = wait_for(stdout, lambda events: any(
        e.get("for") == "e1" and e["ev"] == "train.done" for e in events))
    finished = [e for e in events if e.get("for") == "e1"]
    assert kinds(finished)[-1] == "train.done"


def test_enrol_reports_progress_and_completes(tmp_path):
    # Breaks if _enrol stops wiring `progress` into train.progress events, or
    # stops calling training.enrol, or stops reporting its result in train.done.
    a_take(tmp_path / "voice" / "reference", "reference", 1)
    a_take(tmp_path / "voice" / "reference", "reference", 2)
    speaker_model = tmp_path / "models" / "speaker.onnx"
    speaker_model.parent.mkdir(parents=True, exist_ok=True)
    speaker_model.write_bytes(b"x")
    voice = a_voice(tmp_path, embed_factory=lambda path: (lambda audio: np.array(
        [1.0, 0.0, 0.0, 0.0], dtype=np.float32)))

    stdout = run([{"id": "e1", "cmd": "enrol"}], voice=voice)
    events = until_terminal(stdout)

    assert kinds(events) == ["ready", "train.progress", "train.progress", "train.done"]
    assert all(e["for"] == "e1" for e in events[1:])
    assert events[-1]["takes"] == 2
    assert (tmp_path / "voice" / "reference.npy").is_file()


def test_enrol_failure_becomes_error_with_trainings_own_sentence(tmp_path):
    # Breaks if _enrol swallows the ValueError, rewrites training.py's own
    # message, or maps "no recordings" to the wrong code.
    voice = a_voice(tmp_path)   # reference dir left empty: nothing to enrol from

    stdout = run([{"id": "e1", "cmd": "enrol"}], voice=voice)
    events = until_terminal(stdout)

    assert kinds(events) == ["ready", "error"]
    assert events[-1]["code"] == "NOT_ENOUGH_TAKES"
    assert "no recordings in" in events[-1]["detail"]
    assert events[-1]["for"] == "e1"


def test_train_wake_reports_progress_and_completes(tmp_path):
    # Breaks the same way the enrol equivalent above does, but for
    # training.train_wake_word and its own progress/result shape.
    for n in (1, 2, 3, 4, 5):
        a_take(tmp_path / "voice" / "wake", "wake", n)
    for n in (1, 2, 3):
        a_take(tmp_path / "negative", "clip", n)
    feature_models = tmp_path / "models" / "openwakeword"
    feature_models.mkdir(parents=True, exist_ok=True)
    (feature_models / "melspectrogram.onnx").write_bytes(b"x")
    (feature_models / "embedding_model.onnx").write_bytes(b"x")
    voice = a_voice(tmp_path, sessions_factory=fake_sessions_factory)

    stdout = run([{"id": "w1", "cmd": "train.wake"}], voice=voice)
    events = until_terminal(stdout)

    assert kinds(events)[0] == "ready"
    assert kinds(events)[-1] == "train.done"
    # One train.progress per clip embedded (5 takes + 3 negatives) plus one for
    # the fit step itself.
    assert kinds(events).count("train.progress") == 9
    assert all(e["for"] == "w1" for e in events[1:])
    assert events[-1]["takes"] == 5
    assert events[-1]["negatives"] == 3
    assert (tmp_path / "voice" / "wake-word.npz").is_file()


def test_train_wake_failure_becomes_error_with_trainings_own_sentence(tmp_path):
    # A different failure than the enrol test above: missing feature models
    # rather than missing takes, so this also pins that _training_error_code
    # tells the two apart instead of reporting everything as one code.
    for n in (1, 2, 3, 4, 5):
        a_take(tmp_path / "voice" / "wake", "wake", n)
    for n in (1, 2, 3):
        a_take(tmp_path / "negative", "clip", n)
    voice = a_voice(tmp_path)   # models/openwakeword left empty

    stdout = run([{"id": "w1", "cmd": "train.wake"}], voice=voice)
    events = until_terminal(stdout)

    assert events[-1]["code"] == "NO_FEATURE_MODELS"
    assert "melspectrogram.onnx" in events[-1]["detail"]
    assert events[-1]["for"] == "w1"


def test_narrate_is_still_answered_while_a_training_run_is_in_progress(tmp_path):
    # The proof that record/enrol/train.wake really run on a worker thread and
    # not on the loop itself. Breaks if _enrol calls training.enrol inline: then
    # narrate could only be answered after training finished, and train.done
    # would already be in the event list by the time narration is.
    a_take(tmp_path / "voice" / "reference", "reference", 1)
    speaker_model = tmp_path / "models" / "speaker.onnx"
    speaker_model.parent.mkdir(parents=True, exist_ok=True)
    speaker_model.write_bytes(b"x")
    gate = threading.Event()

    def blocking_embed_factory(_model_path):
        def embed(_audio):
            gate.wait(2.0)
            return np.ones(4, dtype=np.float32)
        return embed

    voice = a_voice(tmp_path, embed_factory=blocking_embed_factory)

    # speak: False, so narrate's reply is exactly one event (narration) and not
    # also a SPEECH_UNAVAILABLE error from the absent speak callable — which
    # would otherwise be a second, unrelated "terminal-looking" event here.
    stdout = run([{"id": "e1", "cmd": "enrol"},
                  {"id": "n1", "cmd": "narrate", "speak": False,
                   "events": [{"summaryHint": "x"}]}], voice=voice)

    events = wait_for(stdout, lambda events: "narration" in kinds(events))
    assert "train.done" not in kinds(events), \
        "training finished before the gate was released: it is not running on a worker thread"

    gate.set()
    events = wait_for(stdout, lambda events: "train.done" in kinds(events))
    assert kinds(events).index("narration") < kinds(events).index("train.done")


def test_the_four_commands_answer_voice_unavailable_without_a_voice_object():
    # Breaks if any of the four falls through to UNKNOWN_COMMAND instead of
    # being recognised and refused — what stub.py and the Java contract test
    # see, since neither ever supplies a voice object.
    events = events_of(run([
        {"id": "s1", "cmd": "voice.status"},
        {"id": "r1", "cmd": "record", "kind": "reference", "takes": 1},
        {"id": "e1", "cmd": "enrol"},
        {"id": "w1", "cmd": "train.wake"},
    ]))

    assert kinds(events) == ["ready", "error", "error", "error", "error"]
    assert [e["code"] for e in events[1:]] == ["VOICE_UNAVAILABLE"] * 4
    assert [e["for"] for e in events[1:]] == ["s1", "r1", "e1", "w1"]
