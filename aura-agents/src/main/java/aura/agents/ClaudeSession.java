package aura.agents;

import aura.core.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A long-lived {@code claude} process with streaming input and output.
 *
 * <p>Turns go out as a line on the same process's stdin, so a follow-up
 * refinement does not pay for a cold start. In exchange the process sits in
 * memory — {@link SessionSupervisor} watches over its termination.
 */
public final class ClaudeSession implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final String sessionId;
    private final Thread stdoutReader;
    private final Thread stderrReader;
    private final Deque<String> recentStderr = new ArrayDeque<>();
    private volatile boolean closing;

    /** Enough to carry the error and its context, not enough to hold a transcript. */
    private static final int STDERR_KEPT = 20;

    private ClaudeSession(Process process, String sessionId, Consumer<AgentEvent> sink,
                          Consumer<String> onUnexpectedExit) {
        this.process = process;
        this.sessionId = sessionId;
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        ClaudeEventParser parser = new ClaudeEventParser(Clock.systemUTC());
        this.stdoutReader = pump(process.getInputStream(), line -> {
            for (AgentEvent event : parser.parseLine(line)) {
                try {
                    sink.accept(event);
                } catch (Exception e) {
                    log.warn("event sink threw an exception", e);
                }
            }
        }, "aura-agent-stdout");

        // Kept, not just logged. The agent's own explanation of why it is about to
        // die goes to stderr, and at debug level it reaches nobody: the user sees a
        // task that does nothing and a log that says everything is fine.
        this.stderrReader = pump(process.getErrorStream(), line -> {
            log.debug("claude stderr: {}", line);
            synchronized (recentStderr) {
                recentStderr.addLast(line);
                if (recentStderr.size() > STDERR_KEPT) {
                    recentStderr.removeFirst();
                }
            }
        }, "aura-agent-stderr");

        process.onExit().thenAccept(exited -> {
            if (closing) {
                // Every session ends. Only the ones that end by themselves are news.
                return;
            }
            String said;
            synchronized (recentStderr) {
                said = String.join(" | ", recentStderr);
            }
            String complaint = "agent exited with code " + exited.exitValue()
                + (said.isBlank() ? " and said nothing" : ": " + said);
            log.warn("{}", complaint);
            onUnexpectedExit.accept(complaint);
        });
    }

    public static ClaudeSession start(SessionConfig config, Consumer<AgentEvent> sink)
            throws Exception {
        return start(config, sink, complaint -> { });
    }

    /**
     * @param onUnexpectedExit told why, when the agent ends without being asked to.
     *                         The caller is the only one who can put it in front of
     *                         the user, and a task that silently does nothing is the
     *                         worst way to find out.
     */
    public static ClaudeSession start(SessionConfig config, Consumer<AgentEvent> sink,
                                      Consumer<String> onUnexpectedExit) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(config.command())
            .directory(config.workingDir().toFile());
        Process process = builder.start();
        log.info("agent started, pid={}, session={}", process.pid(), config.sessionId());
        return new ClaudeSession(process, config.sessionId(), sink, onUnexpectedExit);
    }

    @Override
    public void send(String userText) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "text");
        content.put("text", userText);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.set("content", MAPPER.createArrayNode().add(content));

        ObjectNode line = MAPPER.createObjectNode();
        line.put("type", "user");
        line.set("message", message);

        try {
            stdin.write(line.toString());
            stdin.write('\n');
            stdin.flush();
        } catch (Exception e) {
            throw new IllegalStateException("failed to send the turn to the agent", e);
        }
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        // Set before anything is torn down: whoever is watching the process exit
        // must be able to tell "we ended it" from "it ended".
        closing = true;
        try {
            stdin.close();
        } catch (Exception ignored) {
            // the process may have already closed its end
        }
        // The tree, not just the root: the agent spawns child processes, and
        // an orphaned pytest would keep running if only the parent were killed.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // What actually releases the pump threads is destroyForcibly() above: closing the
        // child's end of the pipes surfaces as EOF on the next read. A blocking read on a
        // process pipe does not answer an interrupt — these calls are belt-and-braces, not
        // the mechanism.
        stdoutReader.interrupt();
        stderrReader.interrupt();
    }

    private static Thread pump(java.io.InputStream in, Consumer<String> onLine, String name) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (Exception e) {
                log.debug("stream {} closed: {}", name, e.toString());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
