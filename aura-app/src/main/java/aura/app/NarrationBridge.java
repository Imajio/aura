package aura.app;

import aura.core.AgentEvent;
import aura.core.NarrationPolicy;
import aura.core.NarrationRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries the agent's event stream to the voice.
 *
 * <p>Two things happen here and nothing else: the policy decides whether this event is
 * worth saying anything about, and what it decides to say gets trimmed to the three
 * fields the narrator can use.
 *
 * <p><b>{@code raw} stays behind.</b> The design says so and it is worth more than a
 * comment: the raw agent JSON would bloat the prompt of the one component with a 900 ms
 * budget, and it is full of paths, ids and tool output nobody should hear read aloud.
 *
 * <p><b>A dead sidecar must not take the event stream with it.</b> Losing the voice is a
 * degradation the user can work around; losing the events is losing sight of the work.
 * So a send that throws is logged and swallowed here, at the boundary, rather than
 * propagating into the stream that feeds the tray and the log.
 */
public final class NarrationBridge {

    private static final Logger log = LoggerFactory.getLogger(NarrationBridge.class);

    private final NarrationPolicy policy;
    private final java.util.function.Consumer<Map<String, Object>> send;
    private final String profile;
    private final boolean voice;
    private final AtomicLong sequence = new AtomicLong();

    public NarrationBridge(NarrationPolicy policy,
                           java.util.function.Consumer<Map<String, Object>> send,
                           String profile,
                           boolean voice) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.send = Objects.requireNonNull(send, "send");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.voice = voice;
    }

    /** Offers an event; speaks only if the policy says this is the moment. */
    public void accept(AgentEvent event) {
        policy.accept(event).ifPresent(this::dispatch);
    }

    /** Called on a schedule: breaks a silence that has gone on too long. */
    public void heartbeat() {
        policy.heartbeat().ifPresent(this::dispatch);
    }

    private void dispatch(NarrationRequest request) {
        Map<String, Object> command = new HashMap<>();
        command.put("id", "n" + sequence.incrementAndGet());
        command.put("cmd", "narrate");
        command.put("style", "progress");
        command.put("profile", profile);
        command.put("priority", request.urgent() ? "urgent" : "normal");
        // Asking for speech that cannot happen is not free: the sidecar answers
        // SPEECH_UNAVAILABLE to every narration, and the log fills with errors
        // describing a configuration nobody got wrong.
        command.put("speak", voice);
        command.put("events", request.events().stream().map(NarrationBridge::forNarrator).toList());

        try {
            send.accept(command);
        } catch (Exception e) {
            log.warn("could not reach the sidecar; narration dropped", e);
        }
    }

    /** The three fields the narrator reads, and nothing that only a log wants. */
    private static Map<String, Object> forNarrator(AgentEvent event) {
        return Map.of(
            "kind", String.valueOf(event.kind()),
            "target", event.target() == null ? "" : event.target(),
            "summaryHint", event.summaryHint() == null ? "" : event.summaryHint());
    }

    /** Visible for the tray: which level the narrator is speaking at. */
    public NarrationPolicy policy() {
        return policy;
    }
}
