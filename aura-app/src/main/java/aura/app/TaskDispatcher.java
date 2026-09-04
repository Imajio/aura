package aura.app;

import aura.agents.AgentSession;
import aura.agents.SessionConfig;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a phrase into a task sent to an agent: resolves the project, assembles
 * the launch command, gets a session from the supervisor, and writes the
 * utterance into it.
 *
 * <p>The project is never guessed. If the phrase names no project and there is
 * no active one yet, the dispatcher reports that honestly instead of picking
 * whichever one comes first.
 */
public final class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    public sealed interface DispatchResult permits Sent, ProjectUnknown {}

    public record Sent(String projectName) implements DispatchResult {}

    public record ProjectUnknown() implements DispatchResult {}

    private final ProjectRegistry registry;
    private final SessionSupervisor supervisor;
    private final AuraConfig config;
    private final Consumer<AgentEvent> sink;
    private volatile String lastProjectName;

    public TaskDispatcher(ProjectRegistry registry, SessionSupervisor supervisor,
                          AuraConfig config, Consumer<AgentEvent> sink) {
        this.registry = registry;
        this.supervisor = supervisor;
        this.config = config;
        this.sink = sink;
    }

    public DispatchResult dispatch(String phrase) {
        Optional<Project> named = registry.resolveFromSpeech(phrase);
        Optional<Project> target = named.isPresent()
            ? named
            : Optional.ofNullable(lastProjectName).flatMap(registry::byName);

        if (target.isEmpty()) {
            return new ProjectUnknown();
        }

        Project project = target.get();
        // Fresh every time, and it has to be. Claude Code refuses to create a
        // session with an id it has already seen — "Session ID ... is already in
        // use" — and the process dies immediately. An id derived from the project
        // name was therefore good for exactly one run in the whole life of that
        // project, and every dispatch after it failed.
        //
        // Nothing is lost by this. The long-lived session of ADR 0004 is the
        // long-lived *process*, which the supervisor keeps and reuses; the id only
        // has to be unique for the process that is about to start.
        String sessionId = UUID.randomUUID().toString();
        Path settings = config.runDir().resolve(project.name() + "-settings.json");

        // The settings file is written before every launch, not once at install
        // time: the socket and jar paths change together with the configuration,
        // and an agent given --settings pointing at a file that doesn't exist
        // simply ends up without a hook — silently, with no error at all.
        SettingsFileWriter.write(settings, config.socketPath(),
            config.hookJar(), config.javaExe());

        SessionConfig sessionConfig = new SessionConfig(
            buildCommand(project, config, sessionId, settings), project.path(), sessionId);

        AgentSession session = supervisor.sessionFor(project.name(), sessionConfig, sink);
        session.send(phrase);
        lastProjectName = project.name();
        log.info("task dispatched to project {}", project.name());
        return new Sent(project.name());
    }

    /** Assembles the launch arguments. Kept static so the test can check it directly. */
    public static List<String> buildCommand(Project project, AuraConfig config,
                                            String sessionId, Path settingsFile) {
        List<String> command = new ArrayList<>();
        if (project.agent() == Agent.CLAUDE) {
            command.add(config.claudeExe().toString());
            command.add("-p");
            command.add("--input-format");
            command.add("stream-json");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            command.add("--include-partial-messages");
            command.add("--session-id");
            command.add(sessionId);
            command.add("--settings");
            command.add(settingsFile.toString());
            command.add("--add-dir");
            command.add(project.path().toString());
            for (Path extra : project.addDirs()) {
                command.add("--add-dir");
                command.add(extra.toString());
            }
        } else {
            command.add(config.codexExe().toString());
            command.add("exec");
            command.add("--json");
            command.add("--sandbox");
            command.add("workspace-write");
            command.add("-C");
            command.add(project.path().toString());
            command.add("--skip-git-repo-check");
            for (Path extra : project.addDirs()) {
                command.add("--add-dir");
                command.add(extra.toString());
            }
        }
        return List.copyOf(command);
    }
}
