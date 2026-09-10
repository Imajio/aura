"""The sidecar's side of the JSON-lines conversation with Java.

One line in, zero or more lines out, UTF-8, no newlines inside a message. The
contract is pinned from the other side too, by `SpeechClientTest` in aura-ipc,
which drives `stub.py`. This module is the implementation that will actually
run; the stub stays exactly as it is, because it is that test's fixture and
because it is the only sidecar that starts on a machine without four gigabytes
of models.

Two rules run through everything here:

**Nothing the model or the speaker does may kill the process.** A sidecar that
dies takes the voice with it, and the user finds out by being met with silence.
Every failure becomes an `error` event with `fatal: false`, and the loop keeps
reading.

**Nothing is claimed that is not done.** When speech cannot play, `speak`
answers with an error rather than an untruthful `speak.done` — the tray would
otherwise show narration working while the user hears nothing.
"""

import json
import pathlib
import threading

from . import recording, training
from .events import to_narrator_input

DEFAULT_PROFILE = "en"
DEFAULT_VERBOSITY = "normal"

# Matches record-voice-samples.py's own WHAT table: a wake take is a short
# burst, a reference take is a sentence long enough to characterise a voice.
_RECORD_SECONDS = {"reference": 6.0, "wake": 2.0}



class VoiceResources:
    """Paths, the listening handle, and the one-at-a-time rule that `record`,
    `enrol` and `train.wake` all share.

    A plain resource bag, not a command object: the sequencing — pause before
    recording and resume after, one worker at a time, a `ValueError` from
    training becoming an `error` event — lives in this module's `_record`,
    `_enrol` and `_train_wake`, where a test can exercise it directly against a
    fake of this class instead of it disappearing into untested wiring in
    main.py, which is the only other place a `VoiceResources` gets built.

    `listening` is Task 2's `Listening`, or `None` when the sidecar was not
    started with `--listen`. `record_take`, `embed_factory` and
    `sessions_factory` default to the real ones — opening a microphone, loading
    26 MB of ONNX — and exist as parameters so a test can hand over a stand-in
    instead, the same way `training.enrol` and `training.train_wake_word`
    already take `embed_factory`/`sessions_factory` for the same reason.
    """

    def __init__(self, *, reference_dir, wake_dir, reference_path, wake_model_path,
                speaker_model_path, feature_models_dir, negative_dir,
                listening=None, record_take=None, embed_factory=None,
                sessions_factory=None):
        self.reference_dir = pathlib.Path(reference_dir)
        self.wake_dir = pathlib.Path(wake_dir)
        self.reference_path = pathlib.Path(reference_path)
        self.wake_model_path = pathlib.Path(wake_model_path)
        self.speaker_model_path = pathlib.Path(speaker_model_path)
        self.feature_models_dir = pathlib.Path(feature_models_dir)
        self.negative_dir = pathlib.Path(negative_dir)
        self.listening = listening
        self.record_take = record_take or recording.record_take
        self.embed_factory = embed_factory
        self.sessions_factory = sessions_factory
        self._lock = threading.Lock()
        self._busy = False

    def try_start(self) -> bool:
        """Claims the one worker slot. False if another one already holds it."""
        with self._lock:
            if self._busy:
                return False
            self._busy = True
            return True

    def finish(self) -> None:
        with self._lock:
            self._busy = False

    def status(self) -> dict:
        """Everything a caller needs to know whether a command can succeed.

        `featureModels` and `negatives` are here for the window's sake. Takes
        are not training's only precondition — `train_wake_word` also needs
        openWakeWord's two ONNX files and a pile of audio that is not the wake
        word — and a client that cannot see them has to offer a button that
        fails, which is exactly what the window is built not to do.
        """
        return {
            "referenceTakes": _wav_count(self.reference_dir),
            "wakeTakes": _wav_count(self.wake_dir),
            "reference": self.reference_path.is_file(),
            "wakeModel": self.wake_model_path.is_file(),
            "speakerModel": self.speaker_model_path.is_file(),
            "featureModels": all((self.feature_models_dir / name).is_file()
                                 for name in training.FEATURE_MODELS),
            # Counted the way training.py collects them, recursively: the
            # narrator audition samples sit in one folder per voice.
            "negatives": _wav_count(self.negative_dir, recursive=True),
            "listening": self.listening.active() if self.listening is not None else False,
        }


def _wav_count(directory, recursive: bool = False) -> int:
    directory = pathlib.Path(directory)
    if not directory.is_dir():
        return 0
    return len(list(directory.rglob("*.wav") if recursive else directory.glob("*.wav")))


def serve(stdin, stdout, narrate, speak=None, cancel=None, devices=None,
          hearing=False, voice=None):
    """Reads commands until the stream ends or `shutdown` arrives.

    `narrate(lines, profile, verbosity) -> str` and `speak(text)` are injected:
    the loop's behaviour is worth testing without loading a model or waking an
    audio device, and which model answers which verbosity is a decision made
    outside this file. `voice` is a `VoiceResources`, or `None` in every build
    that carries no voice support — `stub.py` and the Java contract test among
    them — in which case `voice.status`, `record`, `enrol`, `train.wake` and a
    `configure` carrying `listen` all answer `VOICE_UNAVAILABLE` rather than
    `UNKNOWN_COMMAND`, so a caller can tell "this build cannot" from "I sent
    nonsense".
    """
    emit = _emitter(stdout)
    profile = DEFAULT_PROFILE
    verbosity = DEFAULT_VERBOSITY

    # Announced before anything is loaded. Compiling a model for the iGPU takes
    # tens of seconds, and a sidecar that stays silent until it finishes looks
    # to the tray exactly like one that failed to start.
    emit({"ev": "ready",
          "devices": devices or {"npu": False, "gpu": True},
          "models": {"stt": "lazy" if hearing else "absent",
                     "slm": "lazy",
                     "tts": "lazy" if speak else "absent"}})

    for line in stdin:
        line = line.strip()
        if not line:
            continue

        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            emit({"ev": "error", "code": "BAD_JSON", "detail": line[:200], "fatal": False})
            continue

        command = message.get("cmd")
        message_id = message.get("id", "")

        if command == "shutdown":
            return
        if command == "configure":
            profile = message.get("profile") or profile
            verbosity = message.get("verbosity") or verbosity
            # Only when the field is there. A configure that carries a narration
            # level and nothing else must stay silent, as it always has, or the
            # tray's narration menu would start answering with voice.status.
            if "listen" in message:
                _listen(voice, bool(message.get("listen")), message_id, emit)
            continue
        if command == "narrate":
            _narrate(emit, message, message_id, profile, verbosity, narrate, speak)
            continue
        if command == "speak":
            _speak(emit, message.get("text", ""), message_id, speak)
            continue
        if command == "speak.cancel":
            # The stop word arrives here. Accepted silently when no voice is
            # loaded rather than reported as unknown: the command belongs to the
            # protocol, and an error would teach the caller to stop sending the
            # one thing that interrupts speech.
            if cancel is not None:
                cancel()
            continue
        if command == "voice.status":
            if voice is None:
                emit(_voice_unavailable(message_id))
            else:
                emit({"ev": "voice.status", "for": message_id, **voice.status()})
            continue
        if command == "record":
            if voice is None:
                emit(_voice_unavailable(message_id))
            else:
                _record(voice, message, message_id, emit)
            continue
        if command == "enrol":
            if voice is None:
                emit(_voice_unavailable(message_id))
            else:
                _enrol(voice, message_id, emit)
            continue
        if command == "train.wake":
            if voice is None:
                emit(_voice_unavailable(message_id))
            else:
                _train_wake(voice, message_id, emit)
            continue

        emit({"ev": "error", "code": "UNKNOWN_COMMAND", "detail": str(command), "fatal": False})


def _narrate(emit, message, message_id, profile, verbosity, narrate, speak) -> None:
    lines = to_narrator_input(message.get("events"))
    if not lines:
        # An empty window is silence. Narrating it would mean asking the model
        # to say something about nothing, and it would oblige.
        return

    try:
        text = narrate(lines, profile, verbosity)
    except Exception as e:
        emit({"ev": "error", "code": "NARRATION_FAILED",
              "detail": f"{type(e).__name__}: {e}"[:200], "for": message_id, "fatal": False})
        return

    if not text:
        return

    emit({"ev": "narration", "text": text, "for": message_id})
    if message.get("speak", True):
        _speak(emit, text, message_id, speak)


def _speak(emit, text, message_id, speak) -> None:
    if speak is None:
        emit({"ev": "error", "code": "SPEECH_UNAVAILABLE",
              "detail": "no voice is loaded in this build", "for": message_id,
              "fatal": False})
        return

    emit({"ev": "speak.started", "for": message_id})
    try:
        speak(text)
    except Exception as e:
        emit({"ev": "error", "code": "SPEECH_FAILED",
              "detail": f"{type(e).__name__}: {e}"[:200], "for": message_id, "fatal": False})
        return
    emit({"ev": "speak.done", "for": message_id})


def _listen(voice, wanted: bool, message_id, emit) -> None:
    """Switches listening on or off, and answers with what is true rather than
    with what was asked for.

    The `voice.status` at the end is the whole point of the branch. The window's
    toggle shows the microphone's state from that event and never from the click,
    so a switch that could not take effect is contradicted by the sidecar within
    the same exchange instead of lying until something else asks.

    Synchronous, on the loop thread, unlike `record`, `enrol` and `train.wake`.
    `start()` returns at once; only the path where the device refuses to close
    blocks at all, and that one is bounded by `pause`'s own timeout. Paying five
    seconds in that rare case buys an answer that cannot interleave with a
    recording's own pause and resume, and a test that needs no waiting.
    """
    if voice is None:
        emit(_voice_unavailable(message_id))
        return
    if voice.listening is None:
        # A sidecar started without --listen has no capture thread to start:
        # there is no microphone to switch on, whatever the window shows.
        emit({"ev": "error", "code": "LISTENING_UNAVAILABLE", "for": message_id,
              "fatal": False,
              "detail": "this sidecar was started without listening; set listen: true in "
                        "config.yaml and start Aura again"})
        emit({"ev": "voice.status", "for": message_id, **voice.status()})
        return

    if wanted:
        voice.listening.start()
    elif not voice.listening.pause(5.0):
        # The device did not close, so listening did not stop. Ask for it back
        # rather than leave a capture thread half-stopped, and say so: reporting
        # the microphone as off while it is still open is the one answer this
        # branch must never give.
        voice.listening.start()
        emit({"ev": "error", "code": "MICROPHONE_IN_USE", "for": message_id, "fatal": False,
              "detail": "the microphone was still in use after 5 seconds; listening is "
                        "still on"})
    emit({"ev": "voice.status", "for": message_id, **voice.status()})


def _voice_unavailable(message_id) -> dict:
    return {"ev": "error", "code": "VOICE_UNAVAILABLE", "for": message_id, "fatal": False,
            "detail": "no voice support in this build"}


def _record(voice: VoiceResources, message, message_id, emit) -> None:
    """Records `takes` clips of `kind`, pausing listening first and resuming after.

    Runs on a worker thread once started: `record_take` is the microphone, and
    `listening.pause` can legitimately take up to its own timeout to return, so
    none of this can happen on the thread that has to keep answering
    `speak.cancel`.
    """
    kind = message.get("kind")
    takes = message.get("takes", 1)
    if kind not in _RECORD_SECONDS:
        emit({"ev": "error", "code": "RECORDING_FAILED", "for": message_id, "fatal": False,
              "detail": f"unknown recording kind: {kind!r}"})
        return
    if not isinstance(takes, int) or isinstance(takes, bool) or takes < 1:
        emit({"ev": "error", "code": "RECORDING_FAILED", "for": message_id, "fatal": False,
              "detail": f"takes must be a positive integer, got {takes!r}"})
        return
    if not voice.try_start():
        emit({"ev": "error", "code": "BUSY", "for": message_id, "fatal": False,
              "detail": "another recording or training run is already in progress"})
        return

    directory = voice.reference_dir if kind == "reference" else voice.wake_dir
    seconds = _RECORD_SECONDS[kind]
    emit({"ev": "record.started", "kind": kind, "takes": takes, "for": message_id})

    def work():
        resume_after = False
        try:
            if voice.listening is not None:
                # Whether the microphone goes back on afterwards is decided
                # before it is taken away. Listening the owner switched off is
                # idle already, so pause() answers True at once, and a resume in
                # the `finally` would then open the device nobody asked to
                # open — invisible until the toggle in the window made turning
                # listening off something a person does.
                was_active = voice.listening.active()
                if not voice.listening.pause(5.0):
                    # The caller must not open the device: a recording started on
                    # top of a stream that refused to die produces silence, and
                    # the owner would be told to speak into nothing.
                    emit({"ev": "error", "code": "MICROPHONE_IN_USE", "for": message_id,
                          "fatal": False,
                          "detail": "the microphone was still in use after 5 seconds"})
                    return
                resume_after = was_active

            written = 0
            for _ in range(takes):
                samples = voice.record_take(seconds, None)
                level = recording.loudness(samples)
                number = recording.next_take_number(directory, kind)
                path = recording.take_path(directory, kind, number)
                recording.write_take(path, samples)
                written += 1
                emit({"ev": "record.take", "kind": kind, "number": number, "level": level,
                      "path": str(path), "for": message_id})
            emit({"ev": "record.done", "kind": kind, "takes": written, "for": message_id})
        except Exception as e:
            emit({"ev": "error", "code": "RECORDING_FAILED", "for": message_id, "fatal": False,
                  "detail": f"{type(e).__name__}: {e}"[:200]})
        finally:
            if resume_after:
                voice.listening.resume()
            voice.finish()

    threading.Thread(target=work, name="aura-record", daemon=True).start()


def _enrol(voice: VoiceResources, message_id, emit) -> None:
    if not voice.try_start():
        emit({"ev": "error", "code": "BUSY", "for": message_id, "fatal": False,
              "detail": "another recording or training run is already in progress"})
        return

    def progress(stage, done, total, detail):
        # Must never raise into the worker thread — training.py calls this
        # directly, with nothing of its own catching a misbehaving callback.
        try:
            emit({"ev": "train.progress", "cmd": "enrol", "stage": stage, "done": done,
                  "total": total, "detail": detail, "for": message_id})
        except Exception:
            pass

    def work():
        try:
            result = training.enrol(voice.reference_dir, voice.speaker_model_path,
                                    voice.reference_path, progress=progress,
                                    embed_factory=voice.embed_factory)
            emit({"ev": "train.done", "cmd": "enrol", "for": message_id, **result})
        except ValueError as e:
            emit({"ev": "error", "code": _training_error_code(str(e)), "for": message_id,
                  "fatal": False, "detail": str(e)})
        except Exception as e:
            emit({"ev": "error", "code": "TRAINING_FAILED", "for": message_id, "fatal": False,
                  "detail": f"{type(e).__name__}: {e}"[:200]})
        finally:
            voice.finish()

    threading.Thread(target=work, name="aura-enrol", daemon=True).start()


def _train_wake(voice: VoiceResources, message_id, emit) -> None:
    if not voice.try_start():
        emit({"ev": "error", "code": "BUSY", "for": message_id, "fatal": False,
              "detail": "another recording or training run is already in progress"})
        return

    def progress(stage, done, total, detail):
        try:
            emit({"ev": "train.progress", "cmd": "train.wake", "stage": stage, "done": done,
                  "total": total, "detail": detail, "for": message_id})
        except Exception:
            pass

    def work():
        try:
            result = training.train_wake_word(voice.wake_dir, voice.negative_dir,
                                              voice.feature_models_dir, voice.wake_model_path,
                                              progress=progress,
                                              sessions_factory=voice.sessions_factory)
            emit({"ev": "train.done", "cmd": "train.wake", "for": message_id, **result})
        except ValueError as e:
            emit({"ev": "error", "code": _training_error_code(str(e)), "for": message_id,
                  "fatal": False, "detail": str(e)})
        except Exception as e:
            emit({"ev": "error", "code": "TRAINING_FAILED", "for": message_id, "fatal": False,
                  "detail": f"{type(e).__name__}: {e}"[:200]})
        finally:
            voice.finish()

    threading.Thread(target=work, name="aura-train-wake", daemon=True).start()


def _training_error_code(message: str) -> str:
    """Which fixed error code a `ValueError` message out of training.py maps to.

    training.py's exceptions are plain `ValueError`, deliberately: a human
    sentence naming the file or the number that is wrong, not a code, because
    that is what a person can act on. The window needs a code as well, so this
    reads the one signal there is — the sentence's own wording, pinned by
    test_training.py, which is why a wording change there breaks a test here
    too rather than silently mis-classifying.
    """
    if message.startswith("no recordings in") or "recording(s) in" in message:
        return "NOT_ENOUGH_TAKES"
    if message.startswith("no speaker model at"):
        return "NO_SPEAKER_MODEL"
    if message.startswith("no melspectrogram.onnx in") or \
            message.startswith("no embedding_model.onnx in"):
        return "NO_FEATURE_MODELS"
    return "TRAINING_FAILED"


def _emitter(stdout):
    lock = threading.Lock()

    def emit(payload):
        # A worker thread now emits alongside the loop that reads stdin — record,
        # enrol and train.wake report through this same function from their own
        # thread while the loop is free to keep answering narrate and
        # speak.cancel. Two writers on one stream interleave into unparseable
        # lines without this lock; main.py's own _emit_to carries the same lock
        # for the same reason, for the listening thread's events.
        line = json.dumps(payload, ensure_ascii=False) + "\n"
        with lock:
            stdout.write(line)
            stdout.flush()
    return emit
