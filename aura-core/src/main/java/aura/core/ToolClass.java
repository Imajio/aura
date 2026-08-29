package aura.core;

import java.util.Locale;
import java.util.Map;

/**
 * A tool's class. The narrator speaks about a change of class, not about
 * every call, so classification is part of the domain, not an adapter detail.
 */
public enum ToolClass {
    SEARCH,
    READ,
    EDIT,
    EXEC,
    NET,
    TASK,
    OTHER;

    private static final Map<String, ToolClass> BY_NAME = Map.ofEntries(
        // Claude Code
        Map.entry("grep", SEARCH),
        Map.entry("glob", SEARCH),
        Map.entry("read", READ),
        Map.entry("notebookread", READ),
        Map.entry("edit", EDIT),
        Map.entry("write", EDIT),
        Map.entry("multiedit", EDIT),
        Map.entry("notebookedit", EDIT),
        Map.entry("bash", EXEC),
        Map.entry("powershell", EXEC),
        Map.entry("webfetch", NET),
        Map.entry("websearch", NET),
        Map.entry("task", TASK),
        // Codex: item types from exec --json
        Map.entry("command_execution", EXEC),
        Map.entry("file_change", EDIT),
        Map.entry("web_search", NET),
        Map.entry("mcp_tool_call", OTHER),
        Map.entry("reasoning", OTHER)
    );

    /** Never throws and never returns null: an unknown name is {@link #OTHER}. */
    public static ToolClass of(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return OTHER;
        }
        return BY_NAME.getOrDefault(toolName.toLowerCase(Locale.ROOT), OTHER);
    }
}
