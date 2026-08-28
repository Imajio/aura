package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpeechClientTest {

    private static final Path STUB = Path.of("..", "sidecar", "aura_speech", "stub.py");

    private static String python() {
        String fromEnv = System.getenv("AURA_PYTHON");
        return fromEnv == null ? "python" : fromEnv;
    }

    @BeforeAll
    static void sidecarStubExists() {
        assumeTrue(Files.isRegularFile(STUB), "sidecar stub not found");
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
    void shutdownEndsTheProcess() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        SpeechClient client = startStub(events);
        await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

        client.send(Map.of("id", "c10", "cmd", "shutdown"));

        await().atMost(Duration.ofSeconds(15)).until(() -> !client.alive());
        client.close();
    }
}
