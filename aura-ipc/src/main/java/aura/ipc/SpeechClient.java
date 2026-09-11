package aura.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Speech sidecar client: JSON-lines over stdio.
 *
 * <p>Audio never crosses this boundary in either direction - only commands
 * and events. That is by design: PCM lives in the sidecar, the JVM never
 * touches it.
 */
public final class SpeechClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpeechClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread stdoutReader;
    private final Thread stderrReader;

    private SpeechClient(Process process, Consumer<JsonNode> onEvent) {
        this.process = process;
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        this.stdoutReader = pump(process.getInputStream(), line -> {
            JsonNode event;
            try {
                event = MAPPER.readTree(line);
            } catch (Exception e) {
                log.warn("sidecar sent an unreadable line: {}", line);
                return;
            }
            try {
                onEvent.accept(event);
            } catch (Exception e) {
                log.warn("event consumer threw while handling a sidecar event", e);
            }
        }, "aura-speech-stdout");

        this.stderrReader = pump(process.getErrorStream(),
            line -> log.debug("sidecar: {}", line), "aura-speech-stderr");
    }

    public static SpeechClient start(List<String> command, Path workingDir,
                                     Consumer<JsonNode> onEvent) throws Exception {
        Process process = new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .start();
        log.info("sidecar started, pid={}", process.pid());
        return new SpeechClient(process, onEvent);
    }

    /**
     * Sends one command as a single JSON line.
     *
     * <p>Synchronized because {@code write(json); write('\n'); flush()} is three
     * calls on one shared {@link BufferedWriter}, and callers are not confined to
     * one thread: the EDT (panels, the tray's narration menu), an agent thread
     * ({@code NarrationBridge}) and the idle sweeper's heartbeat all call this on
     * the same {@code SpeechClient}. Two interleaved calls merge into one line
     * neither command's JSON parses as, so the sidecar answers {@code BAD_JSON}
     * and both are lost with no retry - the Python side guards the same hazard
     * on its own send path with a lock, and this is the Java mirror of it.
     */
    public synchronized void send(Map<String, Object> command) {
        try {
            stdin.write(MAPPER.writeValueAsString(command));
            stdin.write('\n');
            stdin.flush();
        } catch (Exception e) {
            throw new IllegalStateException("failed to send command to sidecar", e);
        }
    }

    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (Exception ignored) {
            // sidecar already closed its end
        }
        process.destroy();
        stdoutReader.interrupt();
        stderrReader.interrupt();
    }

    private static Thread pump(java.io.InputStream in, Consumer<String> onLine, String name) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        onLine.accept(line);
                    }
                }
            } catch (Exception e) {
                log.debug("thread {} closed: {}", name, e.toString());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
