"""Turns the events the Java side sends into lines a narrator can read.

`AgentEvent` carries far more than the narrator needs - a tool-use id, a raw
JSON line kept for the log, a session id. Handing all of it to the model would
bloat the prompt and slow the one component with a 900 ms budget, so this keeps
the single field that was put there for exactly this purpose.
"""


def to_narrator_input(events) -> list[str]:
    """One short line per event, in the order they happened.

    `summaryHint` is what the adapters fill in for the narrator. When an event
    has none, its kind and target still say something worth hearing -
    "TOOL_START pytest" is a sentence the model can work with. An event with
    neither is dropped rather than passed on as a blank: a prompt padded with
    empty lines teaches the model to invent.
    """
    lines = []
    for event in events or ():
        hint = (event.get("summaryHint") or "").strip()
        if hint:
            lines.append(hint)
            continue
        described = " ".join(part for part in ((event.get("kind") or "").strip(),
                                               (event.get("target") or "").strip())
                             if part)
        if described:
            lines.append(described)
    return lines
