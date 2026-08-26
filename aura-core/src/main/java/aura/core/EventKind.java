package aura.core;

/**
 * Вид события в канонической форме. Оба адаптера обязаны укладываться в этот
 * набор: всё, что не распознано, становится {@link #OTHER} и не роняет поток.
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
