package aura.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Каноническое событие агента. Оба CLI сводятся к нему, и всё, что выше
 * адаптеров, знает только этот тип.
 *
 * <p>{@code summaryHint} — намеренно короткая выжимка для будущего нарратора,
 * а не полный вывод инструмента. {@code raw} хранится строкой, а не деревом,
 * чтобы модуль остался без зависимости на JSON-библиотеку.
 */
public record AgentEvent(
    Instant ts,
    String sessionId,
    Agent agent,
    EventKind kind,
    ToolClass toolClass,
    String target,
    Boolean ok,
    String summaryHint,
    String toolUseId,
    String parentToolUseId,
    String raw
) {

    public AgentEvent {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toolClass, "toolClass");
        Objects.requireNonNull(target, "target");
        summaryHint = summaryHint == null ? "" : summaryHint;
        raw = raw == null ? "" : raw;
    }

    /** Событие порождено подзадачей, а не основным потоком. */
    public boolean fromSubagent() {
        return parentToolUseId != null && !parentToolUseId.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Instant ts;
        private String sessionId;
        private Agent agent;
        private EventKind kind;
        private ToolClass toolClass = ToolClass.OTHER;
        private String target = "";
        private Boolean ok;
        private String summaryHint = "";
        private String toolUseId;
        private String parentToolUseId;
        private String raw = "";

        public Builder ts(Instant v) { this.ts = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agent(Agent v) { this.agent = v; return this; }
        public Builder kind(EventKind v) { this.kind = v; return this; }
        public Builder toolClass(ToolClass v) { this.toolClass = v; return this; }
        public Builder target(String v) { this.target = v; return this; }
        public Builder ok(Boolean v) { this.ok = v; return this; }
        public Builder summaryHint(String v) { this.summaryHint = v; return this; }
        public Builder toolUseId(String v) { this.toolUseId = v; return this; }
        public Builder parentToolUseId(String v) { this.parentToolUseId = v; return this; }
        public Builder raw(String v) { this.raw = v; return this; }

        public AgentEvent build() {
            return new AgentEvent(ts, sessionId, agent, kind, toolClass, target,
                ok, summaryHint, toolUseId, parentToolUseId, raw);
        }
    }
}
