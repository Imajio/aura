package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import aura.agents.AgentSession;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskDispatcherTest {

    /** For command-assembly checks: the run directory is unused. */
    private static final AuraConfig CONFIG = new AuraConfig(
        Path.of("claude"), Path.of("codex"),
        Path.of("projects.yaml"), Path.of("C:", "aura", "aura-hook.jar"),
        Path.of("C:", "jdk", "bin", "java.exe"), Path.of("C:", "run"),
        Duration.ofMinutes(15), Duration.ofSeconds(20),
        Path.of("sidecar"), Path.of("python"), "", "en",
        Path.of("voice", "wake-word.npz"),
        Path.of("models", "wespeaker-resnet34", "voxceleb_resnet34_LM.onnx"),
        Path.of("voice", "reference.npy"), false);

    /**
     * For dispatch checks: the dispatcher writes the settings file to disk, so
     * the run directory must be temporary. A test that creates C:\run is a
     * test that litters the machine of whoever runs it.
     */
    private static AuraConfig configIn(Path runDir) {
        return new AuraConfig(
            Path.of("claude"), Path.of("codex"),
            Path.of("projects.yaml"), runDir.resolve("aura-hook.jar"),
            Path.of("C:", "jdk", "bin", "java.exe"), runDir,
            Duration.ofMinutes(15), Duration.ofSeconds(20),
            Path.of("sidecar"), Path.of("python"), "", "en",
            Path.of("voice", "wake-word.npz"),
            Path.of("models", "wespeaker-resnet34", "voxceleb_resnet34_LM.onnx"),
            Path.of("voice", "reference.npy"), false);
    }

    private static Project project(String name, Agent agent, String... aliases) {
        return new Project(name, List.of(aliases), Path.of("C:", "work", name), agent,
            List.of(), Set.of("Read"), Set.of("Bash"), Set.of());
    }

    private static final class RecordingSession implements AgentSession {
        final List<String> sent = new ArrayList<>();
        @Override public void send(String userText) { sent.add(userText); }
        @Override public String sessionId() { return "s"; }
        @Override public boolean alive() { return true; }
        @Override public void close() { }
    }

    @Test
    void claudeCommandStreamsBothWaysAndCarriesOurSettings() {
        List<String> command = TaskDispatcher.buildCommand(
            project("backend", Agent.CLAUDE), CONFIG, "11111111-2222-3333-4444-555555555555",
            Path.of("C:", "run", "settings.json"));

        assertThat(command).containsSubsequence("-p", "--input-format", "stream-json");
        assertThat(command).containsSubsequence("--output-format", "stream-json");
        assertThat(command).contains("--verbose");
        assertThat(command).containsSubsequence("--session-id", "11111111-2222-3333-4444-555555555555");
        assertThat(command).containsSubsequence("--settings", "C:\\run\\settings.json");
        assertThat(command).containsSubsequence("--add-dir", "C:\\work\\backend");
    }

    @Test
    void aSessionIdIsNeverReusedAcrossRestarts() throws Exception {
        // Claude Code refuses to create a session with an id it has already seen:
        // "Session ID ... is already in use", and the process dies at once. An id
        // derived from the project name is therefore good for exactly one run in
        // the whole life of that project - after which every dispatch fails.
        Path runDir = Files.createTempDirectory("aura-session-id");
        Set<String> ids = new java.util.HashSet<>();

        for (int restart = 0; restart < 3; restart++) {
            List<String> command = new ArrayList<>();
            TaskDispatcher dispatcher = new TaskDispatcher(
                new ProjectRegistry(List.of(project("backend", Agent.CLAUDE, "backend"))),
                new SessionSupervisor((sessionConfig, sink) -> {
                    command.addAll(sessionConfig.command());
                    return new RecordingSession();
                }, Duration.ofMinutes(15), Clock.systemUTC()),
                configIn(runDir), event -> { });

            dispatcher.dispatch("in project backend do something");
            int idAt = command.indexOf("--session-id") + 1;
            ids.add(command.get(idAt));
        }

        assertThat(ids).hasSize(3);
    }

    @Test
    void claudeCommandNeverBypassesPermissions() {
        List<String> command = TaskDispatcher.buildCommand(
            project("backend", Agent.CLAUDE), CONFIG, "s", Path.of("s.json"));
        assertThat(command).noneMatch(arg -> arg.contains("bypassPermissions"));
        assertThat(command).doesNotContain("--dangerously-skip-permissions");
    }

    @Test
    void codexCommandRunsSandboxedInsideTheProject() {
        List<String> command = TaskDispatcher.buildCommand(
            project("scratch", Agent.CODEX), CONFIG, "s", Path.of("s.json"));

        assertThat(command).containsSubsequence("exec", "--json");
        assertThat(command).containsSubsequence("--sandbox", "workspace-write");
        assertThat(command).containsSubsequence("-C", "C:\\work\\scratch");
        assertThat(command).doesNotContain("--dangerously-bypass-approvals-and-sandbox");
    }

    private static ProjectRegistry twoProjects() {
        return new ProjectRegistry(List.of(
            project("backend", Agent.CLAUDE, "бэкенд"),
            project("frontend", Agent.CLAUDE, "фронтенд")));
    }

    @Test
    void phraseNamingAProjectReachesThatProjectsSession(@TempDir Path tmp) {
        RecordingSession session = new RecordingSession();
        var supervisor = new SessionSupervisor((cfg, sink) -> session,
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        var result = dispatcher.dispatch("в проекте бэкенд почини падающие тесты");

        assertThat(result).isInstanceOf(TaskDispatcher.Sent.class);
        assertThat(((TaskDispatcher.Sent) result).projectName()).isEqualTo("backend");
        assertThat(session.sent).containsExactly("в проекте бэкенд почини падающие тесты");
        supervisor.close();
    }

    @Test
    void dispatchWritesTheSettingsFileTheCommandPointsAt(@TempDir Path tmp) throws Exception {
        // Without this, the agent gets --settings pointing at a file that doesn't exist and
        // ends up without a hook: permissions stop being asked, and nobody notices.
        var supervisor = new SessionSupervisor((cfg, sink) -> new RecordingSession(),
            Duration.ofMinutes(10), Clock.systemUTC());
        AuraConfig config = configIn(tmp);

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, config, e -> {});
        dispatcher.dispatch("в проекте бэкенд почини тесты");

        Path settings = tmp.resolve("backend-settings.json");
        assertThat(settings).exists();
        String content = java.nio.file.Files.readString(settings,
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("PreToolUse");
        // Parsed, not matched as raw text: the file is JSON, so a Windows path's
        // backslashes are escaped in the bytes on disk and never appear there
        // literally - the same reason SettingsFileWriterTest parses before asserting.
        String socketInFile = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(content).at("/env/AURA_HOOK_SOCKET").asText();
        assertThat(socketInFile).isEqualTo(config.socketPath().toString());
        supervisor.close();
    }

    @Test
    void phraseWithoutProjectFallsBackToTheLastActiveOne(@TempDir Path tmp) {
        RecordingSession session = new RecordingSession();
        var supervisor = new SessionSupervisor((cfg, sink) -> session,
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        dispatcher.dispatch("в проекте бэкенд почини тесты");
        var result = dispatcher.dispatch("а теперь прогони линтер");

        assertThat(((TaskDispatcher.Sent) result).projectName()).isEqualTo("backend");
        assertThat(session.sent).hasSize(2);
        supervisor.close();
    }

    @Test
    void unnamedProjectWithoutHistoryIsReportedRatherThanGuessed(@TempDir Path tmp) {
        var supervisor = new SessionSupervisor((cfg, sink) -> new RecordingSession(),
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        var result = dispatcher.dispatch("почини падающие тесты");

        assertThat(result).isInstanceOf(TaskDispatcher.ProjectUnknown.class);
        supervisor.close();
    }
}
