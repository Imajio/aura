package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.AgentEvent;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionSupervisorTest {

    private static final SessionConfig CONFIG =
        new SessionConfig(List.of("java", "-version"), Path.of("."), "s1");

    /** Controlled clock: test should not wait for real idle timeout. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-26T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeSession implements AgentSession {
        boolean closed;
        boolean aliveFlag = true;
        final List<String> sent = new java.util.ArrayList<>();

        @Override public void send(String userText) { sent.add(userText); }
        @Override public String sessionId() { return "s1"; }
        @Override public boolean alive() { return aliveFlag && !closed; }
        @Override public void close() { closed = true; }
    }

    @Test
    void reusesTheSameSessionForTheSameKey() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor(
            (cfg, sink) -> { created.incrementAndGet(); return new FakeSession(); },
            Duration.ofMinutes(10), new MovableClock());

        AgentSession first = supervisor.sessionFor("backend", CONFIG, e -> {});
        AgentSession second = supervisor.sessionFor("backend", CONFIG, e -> {});

        assertThat(second).isSameAs(first);
        assertThat(created).hasValue(1);
        supervisor.close();
    }

    @Test
    void createsSeparateSessionsForSeparateProjects() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor(
            (cfg, sink) -> { created.incrementAndGet(); return new FakeSession(); },
            Duration.ofMinutes(10), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        supervisor.sessionFor("frontend", CONFIG, e -> {});

        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void deadSessionIsReplacedOnNextRequest() throws Exception {
        AtomicInteger created = new AtomicInteger();
        FakeSession[] made = new FakeSession[1];
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            created.incrementAndGet();
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(10), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        made[0].aliveFlag = false;
        supervisor.sessionFor("backend", CONFIG, e -> {});

        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void idleSessionIsClosedAfterTimeout() throws Exception {
        MovableClock clock = new MovableClock();
        FakeSession[] made = new FakeSession[1];
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(5), clock);

        supervisor.sessionFor("backend", CONFIG, e -> {});
        clock.advance(Duration.ofMinutes(4));
        assertThat(supervisor.closeIdle()).isZero();

        clock.advance(Duration.ofMinutes(2));
        assertThat(supervisor.closeIdle()).isEqualTo(1);
        assertThat(made[0].closed).isTrue();
        supervisor.close();
    }

    @Test
    void activityResetsTheIdleClock() throws Exception {
        MovableClock clock = new MovableClock();
        var supervisor = new SessionSupervisor((cfg, sink) -> new FakeSession(),
            Duration.ofMinutes(5), clock);

        supervisor.sessionFor("backend", CONFIG, e -> {});
        clock.advance(Duration.ofMinutes(4));
        supervisor.sessionFor("backend", CONFIG, e -> {});   // touching the session is activity
        clock.advance(Duration.ofMinutes(4));

        assertThat(supervisor.closeIdle()).isZero();
        supervisor.close();
    }

    @Test
    void stopClosesOneSessionAndForgetsIt() throws Exception {
        FakeSession[] made = new FakeSession[1];
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            created.incrementAndGet();
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(5), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        supervisor.stop("backend");

        assertThat(made[0].closed).isTrue();
        supervisor.sessionFor("backend", CONFIG, e -> {});
        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void closeShutsDownEverySession() throws Exception {
        List<FakeSession> made = new java.util.ArrayList<>();
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            FakeSession s = new FakeSession();
            made.add(s);
            return s;
        }, Duration.ofMinutes(5), new MovableClock());

        supervisor.sessionFor("a", CONFIG, e -> {});
        supervisor.sessionFor("b", CONFIG, e -> {});
        supervisor.close();

        assertThat(made).allMatch(s -> s.closed);
    }
}
