package aura.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A fully assembled agent launch command.
 *
 * <p>The command arrives as a ready-made list rather than being built here:
 * the arguments depend on the agent and on the project, and the place that
 * assembles them is {@code aura-app}.
 */
public record SessionConfig(List<String> command, Path workingDir, String sessionId) {
    public SessionConfig {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty agent launch command");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
