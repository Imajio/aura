package aura.agents;

import aura.core.AgentEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        Entry existing = sessions.get(key);
        if (existing != null && existing.session().alive()) {
            sessions.put(key, new Entry(existing.session(), clock.instant()));
            return existing.session();
        }
        if (existing != null) {
            log.info("session {} is dead - recreating", key);
            safeClose(existing.session());
        }
        try {
            AgentSession created = factory.create(config, sink);
            sessions.put(key, new Entry(created, clock.instant()));
            return created;
        } catch (Exception e) {
            sessions.remove(key);
            throw new IllegalStateException("failed to start agent for " + key, e);
        }
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
        List<String> expired = new ArrayList<>();
        sessions.forEach((key, entry) -> {
            if (Duration.between(entry.lastUsed(), now).compareTo(idleTimeout) > 0) {
                expired.add(key);
            }
        });
        expired.forEach(this::stop);
        return expired.size();
    }

    @Override
    public void close() {
        List.copyOf(sessions.keySet()).forEach(this::stop);
    }

    private static void safeClose(AgentSession session) {
        try {
            session.close();
        } catch (Exception e) {
            log.debug("session closed with error: {}", e.toString());
        }
    }
}
