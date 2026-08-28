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
