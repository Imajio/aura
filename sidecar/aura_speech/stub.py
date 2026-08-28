"""Sidecar stub: speaks the protocol but loads no models.

Exists to pin the JSON-lines contract and cover it with tests before real
audio arrives in M2. Plays nothing and listens to nothing.
"""

import json
import sys

# Pin both pipes to UTF-8. A piped child on Windows inherits the console code page
# (cp1252 here), which cannot represent Cyrillic at all and encodes with errors="strict":
# the first Russian narration line would raise UnicodeEncodeError and kill the sidecar,
# with the traceback going to a stderr stream the client logs below its default level.
sys.stdout.reconfigure(encoding="utf-8", newline="\n")
sys.stdin.reconfigure(encoding="utf-8")


def emit(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    emit({
        "ev": "ready",
        "devices": {"npu": False, "gpu": False},
        "models": {"stt": "stub", "slm": "stub", "tts": "stub"},
        "stub": True,
    })

    for line in sys.stdin:
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
            break
        if command == "configure":
            continue
        if command == "speak":
            emit({"ev": "speak.started", "for": message_id})
            emit({"ev": "speak.done", "for": message_id})
            continue
        if command == "narrate":
            events = message.get("events", [])
            emit({"ev": "narration", "text": f"stub: {len(events)} events", "for": message_id})
            emit({"ev": "speak.started", "for": message_id})
            emit({"ev": "speak.done", "for": message_id})
            continue
        if command == "speak.cancel":
            continue

        emit({"ev": "error", "code": "UNKNOWN_COMMAND", "detail": str(command), "fatal": False})


if __name__ == "__main__":
    main()
