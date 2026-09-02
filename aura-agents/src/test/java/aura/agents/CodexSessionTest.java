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

class CodexSessionTest {

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static SessionConfig fakeCodexConfig() {
        return new SessionConfig(
            List.of(java(), "-cp", classpath(), "aura.agents.FakeCodexMain", "exec", "--json"),
            Path.of("."),
            "t-fake");
    }

    @Test
    void firstTurnRunsExecAndProducesEvents() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            session.send("почини тесты");

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));

            assertThat(events).extracting(AgentEvent::kind)
                .containsSequence(EventKind.SESSION_START, EventKind.ASSISTANT_TEXT, EventKind.DONE);
            assertThat(events).anySatisfy(e ->
                assertThat(e.summaryHint()).contains("prompt=").contains("почини тесты"));
        }
    }

    @Test
    void secondTurnAddsResumeToTheCommand() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            session.send("первая");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 1);

            session.send("вторая");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 2);

            List<AgentEvent> texts = events.stream()
                .filter(e -> e.kind() == EventKind.ASSISTANT_TEXT).toList();
            assertThat(texts.get(0).summaryHint()).doesNotContain("resume");
            assertThat(texts.get(1).summaryHint()).contains("resume").contains("--last");
        }
    }

    @Test
    void sessionIsAliveBetweenTurnsEvenThoughNoProcessRuns() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            // Codex has no process between turns, but the session is logically alive:
            // otherwise the supervisor would recreate it on every single turn.
            assertThat(session.alive()).isTrue();
            session.send("что-нибудь");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));
            assertThat(session.alive()).isTrue();
        }
    }

    @Test
    void closeKillsTheProcessTreeNotJustTheParent() throws Exception {
        // The stop word a later milestone builds will rest on this. Killing only the
        // parent leaves an orphaned child (a pytest, an npm run) still working, with
        // nothing left to report to it, while the user believes they stopped it.
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        SessionConfig hanging = new SessionConfig(
            List.of(java(), "-cp", classpath(), "aura.agents.FakeCodexMain",
                "exec", "--json", "--hang"),
            Path.of("."),
            "t-hang");

        long parentPid;
        long childPid;
        try (CodexSession session = CodexSession.start(hanging, events::add)) {
            session.send("почини тесты");

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> e.summaryHint().startsWith("pids=")));

            String pids = events.stream()
                .map(AgentEvent::summaryHint)
                .filter(hint -> hint.startsWith("pids="))
                .findFirst()
                .orElseThrow()
                .substring("pids=".length());
            parentPid = Long.parseLong(pids.split(",")[0]);
            childPid = Long.parseLong(pids.split(",")[1]);

            assertThat(ProcessHandle.of(parentPid)).get().matches(ProcessHandle::isAlive);
            assertThat(ProcessHandle.of(childPid)).get().matches(ProcessHandle::isAlive);
        }

        // Checked as two separate named awaits rather than one combined condition, so a
        // failure here names which one of the two survived close(), not just that the
        // pair did not both go away.
        await("parent process exits").atMost(Duration.ofSeconds(15)).until(() ->
            !ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false));
        await("child process exits").atMost(Duration.ofSeconds(15)).until(() ->
            !ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }
}
