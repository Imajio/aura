package aura.agents;

/**
 * An agent session. Implementations differ in their latency characteristics:
 * for Claude Code the process lives on between turns, while Codex spins up
 * {@code exec resume} on every turn. The asymmetry is named on purpose, not
 * hidden.
 */
public interface AgentSession extends AutoCloseable {

    /** Sends a user turn into the session. */
    void send(String userText);

    String sessionId();

    boolean alive();

    @Override
    void close();
}
