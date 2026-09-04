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

from .events import to_narrator_input

DEFAULT_PROFILE = "en"


def serve(stdin, stdout, narrate, speak=None, devices=None):
    """Reads commands until the stream ends or `shutdown` arrives.

    `narrate(lines, profile) -> str` and `speak(text)` are injected: the loop's
    behaviour is worth testing without loading a model or waking an audio
    device, and which model answers is a decision made outside this file.
    """
    emit = _emitter(stdout)
    profile = DEFAULT_PROFILE

    # Announced before anything is loaded. Compiling a model for the iGPU takes
    # tens of seconds, and a sidecar that stays silent until it finishes looks
    # to the tray exactly like one that failed to start.
    emit({"ev": "ready",
          "devices": devices or {"npu": False, "gpu": True},
          "models": {"stt": "absent", "slm": "lazy",
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
            continue
        if command == "narrate":
            profile = _narrate(emit, message, message_id, profile, narrate, speak)
            continue
        if command == "speak":
            _speak(emit, message.get("text", ""), message_id, speak)
            continue
        if command == "speak.cancel":
            # Nothing to cancel until playback exists. Silently accepted rather
            # than reported as unknown: the command is part of the protocol, and
            # answering an error would teach the caller to stop sending it.
            continue

        emit({"ev": "error", "code": "UNKNOWN_COMMAND", "detail": str(command), "fatal": False})


def _narrate(emit, message, message_id, profile, narrate, speak) -> str:
    lines = to_narrator_input(message.get("events"))
    if not lines:
        # An empty window is silence. Narrating it would mean asking the model
        # to say something about nothing, and it would oblige.
        return profile

    try:
        text = narrate(lines, profile)
    except Exception as e:
        emit({"ev": "error", "code": "NARRATION_FAILED",
              "detail": f"{type(e).__name__}: {e}"[:200], "for": message_id, "fatal": False})
        return profile

    if not text:
        return profile

    emit({"ev": "narration", "text": text, "for": message_id})
    if message.get("speak", True):
        _speak(emit, text, message_id, speak)
    return profile


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


def _emitter(stdout):
    def emit(payload):
        stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
        stdout.flush()
    return emit
