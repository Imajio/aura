package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one place that reads the sidecar's JSON lines - what every subscriber gets,
 * and what happens when a line or a listener misbehaves.
 */
class SidecarEventsTest {

    private final SidecarEvents events = new SidecarEvents();

    @Test
    void aWellFormedLineReachesEverySubscriberWithItsKind() {
        List<SidecarEvent> first = new ArrayList<>();
        List<SidecarEvent> second = new ArrayList<>();
        events.subscribe(first::add);
        events.subscribe(second::add);

        events.onLine("{\"ev\":\"wake\",\"score\":0.9}");

        assertThat(first).hasSize(1);
        assertThat(first.get(0).kind()).isEqualTo("wake");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).kind()).isEqualTo("wake");
    }

    @Test
    void aRussianUtteranceSurvivesTheJsonRoundTrip() throws Exception {
        // Russian test data standing in for the owner's recognised speech - the
        // one carve-out this project's language rule names for non-English
        // content: not prose for a human, but the input and expected result of
        // a check.
        //
        // This also exercises the round trip Main.startSidecar actually does:
        // SpeechClient parses the sidecar's line into a JsonNode first, then
        // Main serialises that node back to text before handing it to onLine,
        // which parses it again. Calling onLine with a literal string would
        // skip that middle step; building the JsonNode first and reading its
        // toString() reproduces it, for the kind of content this field
        // actually carries.
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        JsonNode parsedBySpeechClient = new ObjectMapper()
            .readTree("{\"ev\":\"utterance\",\"text\":\"почини тесты\"}");
        events.onLine(parsedBySpeechClient.toString());

        assertThat(heard.get(0).text("text")).isEqualTo("почини тесты");
    }

    @Test
    void aBrokenSubscriberDoesNotDeafenTheOthersOrStopLaterLines() {
        // Production change this guards against: dropping the per-subscriber
        // try/catch in SidecarEvents.onLine (or wrapping the whole loop in one
        // try/catch instead of one per listener), so a throwing listener either
        // stops the remaining subscribers on this line, or stops the next
        // onLine call from delivering at all. The tray is a subscriber; a
        // broken panel added in a later task must not be able to deafen it.
        List<SidecarEvent> survivor = new ArrayList<>();
        events.subscribe(event -> {
            throw new RuntimeException("a broken panel");
        });
        events.subscribe(survivor::add);

        events.onLine("{\"ev\":\"wake\"}");
        events.onLine("{\"ev\":\"utterance\",\"text\":\"open backend\"}");

        assertThat(survivor).hasSize(2);
        assertThat(survivor.get(0).kind()).isEqualTo("wake");
        assertThat(survivor.get(1).text("text")).isEqualTo("open backend");
    }

    @Test
    void aLineThatIsNotJsonIsDroppedNotThrown() {
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("not json at all");

        // Reaching this assertion at all is part of the proof: a thrown
        // exception here would have failed the test before this line ran.
        assertThat(heard).isEmpty();
    }

    @Test
    void aLineWithNoEvFieldIsDropped() {
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("{\"text\":\"hello\"}");

        assertThat(heard).isEmpty();
    }

    @Test
    void aLineWithEvExplicitlyNullIsDropped() {
        // Distinct from aLineWithNoEvFieldIsDropped: here the "ev" key is
        // present, its value is JSON null. hasNonNull("ev") drops this the
        // same way as a missing key; a later edit that swaps it for the
        // weaker has("ev") - true here, since the key exists even though its
        // value is null - would let this line through, and this test would
        // catch it.
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("{\"ev\":null,\"text\":\"hello\"}");

        assertThat(heard).isEmpty();
    }

    @Test
    void subscribersAreCalledInTheOrderTheySubscribed() {
        // Production change this guards against: a subscriber structure or
        // iteration that does not preserve insertion order - e.g. iterating
        // the listener list back-to-front, or holding listeners in a Set.
        List<String> callOrder = new ArrayList<>();
        events.subscribe(event -> callOrder.add("first"));
        events.subscribe(event -> callOrder.add("second"));
        events.subscribe(event -> callOrder.add("third"));

        events.onLine("{\"ev\":\"ready\"}");

        assertThat(callOrder).containsExactly("first", "second", "third");
    }

    @Test
    void textReadsAStringFieldAndDefaultsToEmptyWhenMissing() {
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("{\"ev\":\"error\",\"code\":\"NO_SPEAKER_REFERENCE\"}");

        SidecarEvent event = heard.get(0);
        assertThat(event.text("code")).isEqualTo("NO_SPEAKER_REFERENCE");
        assertThat(event.text("detail")).isEmpty();
    }

    @Test
    void numberReadsANumericFieldAndDefaultsToZeroWhenMissing() {
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("{\"ev\":\"train.progress\",\"percent\":42}");

        SidecarEvent event = heard.get(0);
        assertThat(event.number("percent")).isEqualTo(42.0);
        assertThat(event.number("missing")).isEqualTo(0.0);
    }

    @Test
    void flagReadsABooleanFieldAndDefaultsToFalseWhenMissing() {
        List<SidecarEvent> heard = new ArrayList<>();
        events.subscribe(heard::add);

        events.onLine("{\"ev\":\"voice.status\",\"enrolled\":true}");

        SidecarEvent event = heard.get(0);
        assertThat(event.flag("enrolled")).isTrue();
        assertThat(event.flag("missing")).isFalse();
    }
}
