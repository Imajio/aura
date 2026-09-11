package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpeechClientTest {

    private static final Path STUB = Path.of("..", "sidecar", "aura_speech", "stub.py");

    private static String python() {
        String fromEnv = System.getenv("AURA_PYTHON");
        return fromEnv == null ? "python" : fromEnv;
    }

    @BeforeAll
    static void aRunnableInterpreterIsAvailable() {
        assumeTrue(Files.isRegularFile(STUB), "sidecar stub not found");
        // Checking that stub.py exists proves nothing - it is tracked and always present.
        // What varies between machines is Python, so ask Python.
        try {
            Process probe = new ProcessBuilder(python(), "--version")
                .redirectErrorStream(true)
                .start();
            assumeTrue(probe.waitFor(20, TimeUnit.SECONDS), "python did not answer --version");
            assumeTrue(probe.exitValue() == 0, "python --version exited non-zero");
        } catch (IOException e) {
            Assumptions.abort("no runnable python interpreter: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assumptions.abort("interrupted while probing for python");
        }
    }

    private SpeechClient startStub(List<JsonNode> sink) throws Exception {
        return SpeechClient.start(List.of(python(), STUB.toString()), Path.of("."), sink::add);
    }

    @Test
    void announcesItselfReadyOnStart() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());
            assertThat(events.get(0).path("ev").asText()).isEqualTo("ready");
            assertThat(events.get(0).path("stub").asBoolean()).isTrue();
        }
    }

    @Test
    void speakCommandIsAcknowledgedByStartAndDone() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "c2", "cmd", "speak", "text", "Claude got to work"));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "speak.done".equals(e.path("ev").asText())));

            assertThat(events).extracting(e -> e.path("ev").asText())
                .containsSequence("speak.started", "speak.done");
            assertThat(events).filteredOn(e -> "speak.started".equals(e.path("ev").asText()))
                .allMatch(e -> "c2".equals(e.path("for").asText()));
        }
    }

    @Test
    void narrateProducesNarrationBeforeSpeaking() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "c4", "cmd", "narrate",
                "style", "progress", "events", List.of(Map.of("kind", "TOOL_START"))));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "narration".equals(e.path("ev").asText())));

            assertThat(events).extracting(e -> e.path("ev").asText())
                .containsSequence("narration", "speak.started", "speak.done");
        }
    }

    @Test
    void unknownCommandYieldsErrorEventInsteadOfSilence() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "cX", "cmd", "fly_to_the_moon"));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "error".equals(e.path("ev").asText())));

            assertThat(events).filteredOn(e -> "error".equals(e.path("ev").asText()))
                .allMatch(e -> "UNKNOWN_COMMAND".equals(e.path("code").asText()));
        }
    }

    @Test
    void concurrentSendsNeverInterleaveIntoAnUnparseableLine() throws Exception {
        // send() is three calls on one shared BufferedWriter - write(json),
        // write('\n'), flush() - and callers are not confined to one thread in
        // production (the EDT, an agent thread, the idle sweeper's heartbeat all
        // reach the same SpeechClient). Without synchronized on send() itself,
        // one thread's write(json) and another thread's whole call can land back
        // to back in the writer before either newline lands, merging two commands
        // into one line neither parses as - the sidecar answers BAD_JSON and both
        // are lost. Reverting the synchronized keyword and rerunning this test
        // reproduces that: it fails with error events and missing ids.
        int threadCount = 16;
        int perThread = 25;
        int total = threadCount * perThread;
        // Long enough that write(json) alone holds the writer's lock for a
        // measurable stretch, so a thread that just released it has to
        // re-compete for the next call rather than sail straight through -
        // widening the exact gap the missing synchronized leaves open.
        String padding = "x".repeat(4000);

        List<String> ids = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            for (int i = 0; i < perThread; i++) {
                ids.add("c" + t + "-" + i);
            }
        }

        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            List<Thread> senders = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                int start = t * perThread;
                senders.add(new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String id = ids.get(start + i);
                        client.send(Map.of("id", id, "cmd", "speak", "text", padding));
                    }
                }, "sender-" + t));
            }
            for (Thread sender : senders) {
                sender.start();
            }
            for (Thread sender : senders) {
                sender.join(30_000);
            }

            await().atMost(Duration.ofSeconds(30)).until(() -> events.stream()
                .filter(e -> "speak.done".equals(e.path("ev").asText())).count() >= total
                || events.stream().anyMatch(e -> "error".equals(e.path("ev").asText())));

            assertThat(events).filteredOn(e -> "error".equals(e.path("ev").asText()))
                .as("no interleaved line should ever reach the sidecar unparseable").isEmpty();

            Set<String> started = new HashSet<>();
            Set<String> done = new HashSet<>();
            for (JsonNode e : events) {
                String ev = e.path("ev").asText();
                if ("speak.started".equals(ev)) {
                    started.add(e.path("for").asText());
                } else if ("speak.done".equals(ev)) {
                    done.add(e.path("for").asText());
                }
            }
            assertThat(started).as("every sent id got its own speak.started, none merged away")
                .containsExactlyInAnyOrderElementsOf(ids);
            assertThat(done).as("every sent id got its own speak.done, none merged away")
                .containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    @Test
    void shutdownEndsTheProcess() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        SpeechClient client = startStub(events);
        await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

        client.send(Map.of("id", "c10", "cmd", "shutdown"));

        await().atMost(Duration.ofSeconds(15)).until(() -> !client.alive());
        client.close();
    }
}
