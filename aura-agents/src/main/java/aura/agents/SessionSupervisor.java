package aura.agents;

import aura.core.AgentEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages one session per project and tracks their lifecycle.
 *
 * <p>Clock is injected from outside: idle timeout should be checked by tests in
 * milliseconds, not in real minutes.
 */
public final class SessionSupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionSupervisor.class);

    @FunctionalInterface
    public interface SessionFactory {
        AgentSession create(SessionConfig config, Consumer<AgentEvent> sink) throws Exception;
    }

    private record Entry(AgentSession session, Instant lastUsed) {}

    private final SessionFactory factory;
    private final Duration idleTimeout;
    private final Clock clock;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    public SessionSupervisor(SessionFactory factory, Duration idleTimeout, Clock clock) {
        this.factory = factory;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    /** Returns a live session for the key, creating or recreating it as needed. */
    public AgentSession sessionFor(String key, SessionConfig config, Consumer<AgentEvent> sink) {
        // Entire decision happens inside compute: read, judge, replace. Split into
        // separate map calls, and two callers arriving together each create a session
        // while the second overwrites the first — leaving a live agent process nobody owns.
        Entry entry = sessions.compute(key, (name, existing) -> {
            if (existing != null && existing.session().alive()) {
                return new Entry(existing.session(), clock.instant());
            }
            if (existing != null) {
                log.info("session {} is dead - recreating", name);
                safeClose(existing.session());
            }
            try {
                return new Entry(factory.create(config, sink), clock.instant());
            } catch (Exception e) {
                throw new IllegalStateException("failed to start agent for " + name, e);
            }
        });
        return entry.session();
    }

    public void stop(String key) {
        Entry entry = sessions.remove(key);
        if (entry != null) {
            log.info("stopping session {}", key);
            safeClose(entry.session());
        }
    }

    /** Closes sessions that haven't been accessed longer than the timeout. */
    public int closeIdle() {
        Instant now = clock.instant();
        int closed = 0;
        for (Map.Entry<String, Entry> mapped : sessions.entrySet()) {
            Entry entry = mapped.getValue();
            if (Duration.between(entry.lastUsed(), now).compareTo(idleTimeout) <= 0) {
                continue;
            }
            // Compare-and-remove: a caller that refreshed this key since the scan began
            // has replaced the entry, and this sweep must leave their session alone.
            if (sessions.remove(mapped.getKey(), entry)) {
                log.info("closing idle session {}", mapped.getKey());
                safeClose(entry.session());
                closed++;
            }
        }
        return closed;
    }

    @Override
    public void close() {
        List.copyOf(sessions.keySet()).forEach(this::stop);
    }

    private static void safeClose(AgentSession session) {
        try {
            session.close();
        } catch (Exception e) {
            log.warn("session closed with error: {}", e.toString(), e);
        }
    }
}
