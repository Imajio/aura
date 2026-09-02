package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ToolClassTest {

    @ParameterizedTest
    @CsvSource({
        "Grep,     SEARCH",
        "Glob,     SEARCH",
        "Read,     READ",
        "Edit,     EDIT",
        "Write,    EDIT",
        "NotebookEdit, EDIT",
        "Bash,     EXEC",
        "PowerShell,   EXEC",
        "WebFetch, NET",
        "WebSearch,    NET",
        "Task,     TASK",
        "Skill,    OTHER"
    })
    void mapsClaudeToolNames(String toolName, ToolClass expected) {
        assertThat(ToolClass.of(toolName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "command_execution, EXEC",
        "file_change,       EDIT",
        "web_search,        NET",
        "mcp_tool_call,     OTHER",
        "reasoning,         OTHER"
    })
    void mapsCodexItemTypes(String itemType, ToolClass expected) {
        assertThat(ToolClass.of(itemType)).isEqualTo(expected);
    }

    @Test
    void unknownNameIsOtherAndNeverThrows() {
        assertThat(ToolClass.of("СовершенноНовыйИнструмент")).isEqualTo(ToolClass.OTHER);
        assertThat(ToolClass.of(null)).isEqualTo(ToolClass.OTHER);
        assertThat(ToolClass.of("")).isEqualTo(ToolClass.OTHER);
    }
}
