package aura.agents;

import aura.core.AgentEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Codex session. {@code codex exec} has no streaming input, so each turn
 * spins up its own process: the first one as-is, later ones with
 * {@code resume --last}. Context is kept on Codex's side.
 *
 * <p>The price for this is a cold start on every turn. The asymmetry with
 * Claude Code is recorded in ADR 0004 and is deliberately not hidden behind
 * a uniform-looking interface.
 */
public final class CodexSession implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(CodexSession.class);

    private final SessionConfig config;
    private final Consumer<AgentEvent> sink;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile Process current;

    private CodexSession(SessionConfig config, Consumer<AgentEvent> sink) {
        this.config = config;
        this.sink = sink;
    }

    public static CodexSession start(SessionConfig config, Consumer<AgentEvent> sink) {
        return new CodexSession(config, sink);
    }

    @Override
    public void send(String userText) {
        if (closed) {
            throw new IllegalStateException("Codex session is closed");
        }
        List<String> command = new ArrayList<>(config.command());
        if (started) {
            // exec resume --last: continue the same thread instead of starting a new one
            int execIndex = command.indexOf("exec");
            command.add(execIndex < 0 ? command.size() : execIndex + 1, "resume");
            command.add(execIndex < 0 ? command.size() : execIndex + 2, "--last");
        }

        try {
            Process process = new ProcessBuilder(command)
                .directory(config.workingDir().toFile())
                .start();
            current = process;
            started = true;

            try (Writer stdin = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                stdin.write(userText);
            }

            CodexEventParser parser = new CodexEventParser(Clock.systemUTC());
            Thread reader = new Thread(() -> readStream(process, parser), "aura-codex-stdout");
            reader.setDaemon(true);
            reader.start();

            // Drained, not ignored. A pipe nobody reads fills up, and a child
            // process blocked writing to it stops producing events without ever
            // saying why - which looks exactly like an agent that is thinking.
            Thread errors = new Thread(() -> drainStderr(process), "aura-codex-stderr");
            errors.setDaemon(true);
            errors.start();
        } catch (Exception e) {
            throw new IllegalStateException("failed to start codex", e);
        }
    }

    private void drainStderr(Process process) {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("codex stderr: {}", line);
            }
        } catch (Exception e) {
            log.debug("codex stderr closed: {}", e.toString());
        }
    }

    private void readStream(Process process, CodexEventParser parser) {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (AgentEvent event : parser.parseLine(line)) {
                    try {
                        sink.accept(event);
                    } catch (Exception e) {
                        log.warn("event sink threw an exception", e);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("codex stream closed: {}", e.toString());
        }
    }

    @Override
    public String sessionId() {
        return config.sessionId();
    }

    /** Alive means "ready to accept an utterance," not "a process is running." */
    @Override
    public boolean alive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        Process process = current;
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
