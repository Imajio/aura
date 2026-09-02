package aura.core;

/**
 * An event kind in canonical form. Both adapters must fit into this set:
 * anything unrecognised becomes {@link #OTHER} and does not drop the stream.
 */
public enum EventKind {
    SESSION_START,
    ASSISTANT_TEXT,
    TOOL_START,
    TOOL_END,
    ERROR,
    TEST_RESULT,
    PERMISSION_REQUEST,
    SUBAGENT_START,
    SUBAGENT_END,
    DONE,
    OTHER
}
