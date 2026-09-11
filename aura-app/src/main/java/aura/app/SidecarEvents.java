package aura.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that reads the sidecar's JSON-lines protocol.
 *
 * <p>The sidecar prints one JSON object per line, each carrying an {@code ev}
 * field that names the kind of event. Parsing every line used to happen once,
 * inline in {@code Main.startSidecar}'s {@code if}-chain; now it happens once
 * here, and {@code Main} is the first of possibly several subscribers. A later
 * reader - the desktop window - can listen to the same lines by subscribing
 * too, instead of copying the chain (which drifts from this one) or holding a
 * reference to {@code Main} (which makes the tray and the window each other's
 * dependency).
 *
 * <p>This class does not know what any {@code ev} kind means; it only carries
 * whatever the sidecar sent to whoever asked to hear it. Two kinds of trouble
 * are swallowed rather than thrown, because the sidecar is a separate process
 * and neither its output nor a subscriber's bug should be able to take the
 * others down:
 *
 * <ul>
 *   <li>a line that is not JSON, or has no {@code ev} field, is dropped;
 *   <li>a subscriber that throws is logged and skipped, so the remaining
 *       subscribers still hear this line, and the next line still reaches
 *       everyone.
 * </ul>
 */
public final class SidecarEvents {

    private static final Logger log = LoggerFactory.getLogger(SidecarEvents.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Copy-on-write because subscribers are added a handful of times at
    // startup and read on every sidecar line: writes are rare, reads are
    // constant, and iteration must never throw ConcurrentModificationException
    // if a later task subscribes something after the sidecar is already
    // running.
    private final List<Consumer<SidecarEvent>> subscribers = new CopyOnWriteArrayList<>();

    /**
     * One event from the sidecar.
     *
     * @param kind the value of the line's {@code ev} field
     * @param body the full parsed line, including {@code ev} itself
     */
    public record SidecarEvent(String kind, JsonNode body) {

        /** The named field as text, or {@code ""} if it is absent. */
        public String text(String field) {
            return body.path(field).asText();
        }

        /** The named field as a number, or {@code 0} if it is absent. */
        public double number(String field) {
            return body.path(field).asDouble();
        }

        /** The named field as a boolean, or {@code false} if it is absent. */
        public boolean flag(String field) {
            return body.path(field).asBoolean();
        }
    }

    /**
     * Registers a subscriber. Subscribers are notified in the order they
     * subscribed.
     */
    public void subscribe(Consumer<SidecarEvent> listener) {
        subscribers.add(listener);
    }

    /**
     * Parses one line from the sidecar and notifies every subscriber, in
     * subscription order.
     *
     * <p>A line that is not valid JSON, or is valid JSON without an {@code ev}
     * field, is dropped without notifying anyone. A subscriber that throws is
     * logged and does not stop the remaining subscribers from hearing this
     * line, nor the next line from being parsed and delivered.
     */
    public void onLine(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.debug("sidecar line is not JSON, dropped: {}", json);
            return;
        }
        if (root == null || !root.hasNonNull("ev")) {
            log.debug("sidecar line has no 'ev' field, dropped: {}", json);
            return;
        }
        SidecarEvent event = new SidecarEvent(root.path("ev").asText(), root);
        for (Consumer<SidecarEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.warn("a sidecar event subscriber threw, continuing with the others", e);
            }
        }
    }
}
