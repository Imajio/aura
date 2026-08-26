package aura.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentEventTest {

    @Test
    void builderFillsDefaultsForOptionalFields() {
        AgentEvent e = AgentEvent.builder()
            .ts(Instant.parse("2026-08-26T10:00:00Z"))
            .sessionId("s1")
            .agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START)
            .toolClass(ToolClass.EXEC)
            .target("echo hi")
            .build();

        assertThat(e.ok()).isNull();
        assertThat(e.summaryHint()).isEmpty();
        assertThat(e.parentToolUseId()).isNull();
        assertThat(e.raw()).isEmpty();
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> AgentEvent.builder().build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void subagentEventIsRecognisedByParentId() {
        AgentEvent own = AgentEvent.builder()
            .ts(Instant.EPOCH).sessionId("s").agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START).toolClass(ToolClass.READ).target("a.java")
            .build();
        AgentEvent nested = AgentEvent.builder()
            .ts(Instant.EPOCH).sessionId("s").agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START).toolClass(ToolClass.READ).target("b.java")
            .parentToolUseId("toolu_123")
            .build();

        assertThat(own.fromSubagent()).isFalse();
        assertThat(nested.fromSubagent()).isTrue();
    }
}
