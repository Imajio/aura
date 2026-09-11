package aura.core;

import java.util.List;
import java.util.Objects;

/**
 * A window of events the narrator should say something about.
 *
 * @param events  everything that happened since the last thing said, in order
 * @param urgent  true when the user is being waited on - a permission request
 *                jumps whatever queue exists, because the agent is blocked until
 *                they answer and they cannot answer what they were not told
 */
public record NarrationRequest(List<AgentEvent> events, boolean urgent) {

    public NarrationRequest {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }
}
