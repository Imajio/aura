package aura.policy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A tool request as seen by the hook: tool name, raw JSON arguments,
 * and the agent's working directory.
 */
public record ToolRequest(String toolName, String toolInputJson, Path cwd) {
    public ToolRequest {
        Objects.requireNonNull(toolName, "toolName");
        toolInputJson = toolInputJson == null ? "{}" : toolInputJson;
        Objects.requireNonNull(cwd, "cwd");
    }
}
