package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.NarrationPolicy;
import aura.core.ToolClass;
import aura.core.Verbosity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What reaches the sidecar, and — the part with teeth — what does not. */
class NarrationBridgeTest {

    private final List<Map<String, Object>> sent = new ArrayList<>();
    private Instant now = Instant.parse("2026-09-04T10:00:00Z");

    private NarrationBridge bridge(Verbosity verbosity) {
        return bridge(verbosity, true);
    }

    private NarrationBridge bridge(Verbosity verbosity, boolean voice) {
        return new NarrationBridge(
            new NarrationPolicy(verbosity, () -> now), sent::add, "ru", voice);
    }

    private AgentEvent event(EventKind kind, ToolClass toolClass, String target, String hint) {
        return AgentEvent.builder()
            .ts(now)
            .sessionId("s1")
            .agent(Agent.CLAUDE)
            .kind(kind)
            .toolClass(toolClass)
            .target(target)
            .summaryHint(hint)
            .raw("{\"a lot of\":\"json nobody should hear\"}")
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventsOf(Map<String, Object> command) {
        return (List<Map<String, Object>>) command.get("events");
    }

    @Test
    void aTriggeringEventBecomesANarrateCommand() {
        bridge(Verbosity.NORMAL).accept(event(EventKind.TEST_RESULT, ToolClass.EXEC,
            "pytest", "3 passed, 1 failed"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).containsEntry("cmd", "narrate");
        assertThat(eventsOf(sent.get(0))).hasSize(1);
    }

    @Test
    void rawAgentJsonNeverReachesTheNarrator() {
        // Stated in the design and worth a test rather than a comment: raw is for
        // the log. In the prompt it would bloat the context of the one component
        // with a 900 ms budget, and it is full of things nobody should hear read
        // out loud.
        bridge(Verbosity.NORMAL).accept(event(EventKind.TEST_RESULT, ToolClass.EXEC,
            "pytest", "3 passed, 1 failed"));

        Map<String, Object> event = eventsOf(sent.get(0)).get(0);
        assertThat(event).containsOnlyKeys("kind", "target", "summaryHint");
        assertThat(event).containsEntry("summaryHint", "3 passed, 1 failed");
    }

    @Test
    void anEventThePolicyIgnoresSendsNothing() {
        bridge(Verbosity.QUIET).accept(event(EventKind.TOOL_START, ToolClass.READ, "a.txt", "read"));

        assertThat(sent).isEmpty();
    }

    @Test
    void theLanguageProfileIsCarriedWithEveryRequest() {
        bridge(Verbosity.NORMAL).accept(event(EventKind.DONE, ToolClass.OTHER, "", "finished"));

        assertThat(sent.get(0)).containsEntry("profile", "ru");
    }

    @Test
    void eachRequestCarriesItsOwnIdSoAnswersCanBeMatched() {
        NarrationBridge bridge = bridge(Verbosity.NORMAL);
        bridge.accept(event(EventKind.TEST_RESULT, ToolClass.EXEC, "pytest", "one"));
        now = now.plusSeconds(10);
        bridge.accept(event(EventKind.TEST_RESULT, ToolClass.EXEC, "pytest", "two"));

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).get("id")).isNotEqualTo(sent.get(1).get("id"));
    }

    @Test
    void aPermissionRequestIsMarkedUrgent() {
        bridge(Verbosity.QUIET).accept(event(EventKind.PERMISSION_REQUEST, ToolClass.EXEC,
            "rm -rf build", "delete the build folder"));

        assertThat(sent.get(0)).containsEntry("priority", "urgent");
    }

    @Test
    void ordinaryNarrationIsNotUrgent() {
        bridge(Verbosity.NORMAL).accept(event(EventKind.DONE, ToolClass.OTHER, "", "finished"));

        assertThat(sent.get(0)).containsEntry("priority", "normal");
    }

    @Test
    void withoutAVoiceTheSidecarIsNotAskedToSpeak() {
        // Asking anyway is not harmless: the sidecar answers SPEECH_UNAVAILABLE to
        // every single narration, and the log fills with errors describing a
        // configuration that is deliberate.
        bridge(Verbosity.NORMAL, false)
            .accept(event(EventKind.DONE, ToolClass.OTHER, "", "finished"));

        assertThat(sent.get(0)).containsEntry("speak", false);
    }

    @Test
    void withAVoiceItIsAskedToSpeak() {
        bridge(Verbosity.NORMAL, true)
            .accept(event(EventKind.DONE, ToolClass.OTHER, "", "finished"));

        assertThat(sent.get(0)).containsEntry("speak", true);
    }

    @Test
    void theHeartbeatSpeaksWhenTheCeilingHasPassed() {
        NarrationBridge bridge = bridge(Verbosity.NORMAL);
        bridge.accept(event(EventKind.TOOL_END, ToolClass.READ, "a.txt", "read a file"));
        assertThat(sent).isEmpty();

        now = now.plus(Duration.ofSeconds(30));
        bridge.heartbeat();

        assertThat(sent).hasSize(1);
    }

    @Test
    void aFailingChannelDoesNotStopTheEventStream() {
        // The sidecar dying must not take the agent's event stream with it. Losing
        // the voice is a degradation; losing the events is losing the work.
        NarrationBridge bridge = new NarrationBridge(
            new NarrationPolicy(Verbosity.NORMAL, () -> now),
            command -> { throw new IllegalStateException("sidecar is gone"); },
            "en", true);

        bridge.accept(event(EventKind.TEST_RESULT, ToolClass.EXEC, "pytest", "one"));
        now = now.plusSeconds(10);
        bridge.accept(event(EventKind.TEST_RESULT, ToolClass.EXEC, "pytest", "two"));
        // Reaching here at all is the assertion: neither call threw.
    }

    @Test
    void eventsWithoutAHintStillCarryTheirKindAndTarget() {
        bridge(Verbosity.NORMAL).accept(event(EventKind.DONE, ToolClass.EXEC, "npm test", null));

        Map<String, Object> event = eventsOf(sent.get(0)).get(0);
        assertThat(event).containsEntry("kind", "DONE");
        assertThat(event).containsEntry("target", "npm test");
    }
}
