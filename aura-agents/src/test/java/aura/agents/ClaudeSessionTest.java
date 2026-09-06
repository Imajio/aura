package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import aura.core.AgentEvent;
import aura.core.EventKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ClaudeSessionTest {

    /** Launches the fake agent with the current JVM: same java, same classpath. */
    private static SessionConfig fakeAgentConfig() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return new SessionConfig(
            List.of(java, "-cp", classpath, "aura.agents.FakeAgentMain", "s-test"),
            Path.of("."),
            "s-test");
    }

    /** Launches the agent that writes one line to stderr and exits non-zero. */
    private static SessionConfig dyingAgentConfig(String message) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return new SessionConfig(
            List.of(java, "-cp", classpath, "aura.agents.DyingAgentMain", message),
            Path.of("."),
            "s-dying");
    }

    @Test
    void theAgentDoesNotInheritAnApiKeyFromTheEnvironment() {
        // Reported by the owner: every task finished in under a second answering
        // "Credit balance is too low". ANTHROPIC_API_KEY was set on the machine for
        // an unrelated script, the CLI prefers it over the claude.ai login Aura is
        // meant to run under, and the whole environment is inherited.
        java.util.Map<String, String> environment = new java.util.HashMap<>();
        environment.put("ANTHROPIC_API_KEY", "sk-ant-something");
        environment.put("PATH", "C:\\Windows");

        ClaudeSession.asTheOwner(environment);

        assertThat(environment).doesNotContainKey("ANTHROPIC_API_KEY");
        // Everything else is the machine the owner works on and has to survive:
        // without PATH the agent binary does not resolve at all.
        assertThat(environment).containsEntry("PATH", "C:\\Windows");
    }

    @Test
    void anAgentThatDiesOnStartupSaysWhy() throws Exception {
        // The failure this exists for: claude rejects a session id it has already
        // seen and exits at once. Everything worked as designed and the user saw
        // nothing happen at all, because the only explanation went to a stream
        // logged below the level anyone reads.
        List<String> complaints = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(
                dyingAgentConfig("Session ID s-dying is already in use."),
                event -> { },
                complaints::add)) {

            await().atMost(Duration.ofSeconds(10)).until(() -> !complaints.isEmpty());

            assertThat(complaints.get(0)).contains("already in use");
            assertThat(session.alive()).isFalse();
        }
    }

    @Test
    void aDeliberateCloseIsNotReportedAsAFailure() throws Exception {
        // Every session ends eventually. Only the ones that end by themselves are
        // news; announcing the others would teach the reader to ignore both.
        List<String> complaints = new CopyOnWriteArrayList<>();
        ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), event -> { },
            complaints::add);
        await().atMost(Duration.ofSeconds(10)).until(session::alive);

        session.close();
        Thread.sleep(500);

        assertThat(complaints).isEmpty();
    }

    @Test
    void emitsSessionStartOnLaunch() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            await().atMost(Duration.ofSeconds(10))
                .until(() -> !events.isEmpty());
            assertThat(events.get(0).kind()).isEqualTo(EventKind.SESSION_START);
            assertThat(session.alive()).isTrue();
        }
    }

    @Test
    void userTurnGoesIntoTheSameProcessAndProducesEvents() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            session.send("почини тесты");

            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));

            assertThat(events).extracting(AgentEvent::kind).contains(
                EventKind.SESSION_START, EventKind.TOOL_START,
                EventKind.TOOL_END, EventKind.DONE);
        }
    }

    @Test
    void secondTurnReusesTheSameProcessWithoutRestart() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            session.send("первая задача");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 1);

            session.send("вторая задача");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 2);

            // Exactly one SESSION_START across two turns — the process was not restarted.
            assertThat(events).filteredOn(e -> e.kind() == EventKind.SESSION_START).hasSize(1);
        }
    }

    @Test
    void closeTerminatesTheProcess() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add);
        await().atMost(Duration.ofSeconds(10)).until(() -> !events.isEmpty());

        session.close();

        await().atMost(Duration.ofSeconds(10)).until(() -> !session.alive());
    }
}
