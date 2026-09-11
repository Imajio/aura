package aura.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decides when the narrator opens its mouth.
 *
 * <p>A line is born from a <b>change in the state of the work</b>, not from a tick of
 * the clock. The clock only bounds it at both ends: a floor so a burst of tool calls
 * does not become a burst of speech, and a ceiling so a long quiet stretch does not
 * leave the user unable to tell a thinking agent from a dead one.
 *
 * <p>Both failure modes are user-visible and neither looks like a bug. Narrating too
 * often produces an application nobody can think next to; narrating too rarely produces
 * one that appears to have crashed. This class is where that balance lives, which is
 * why it holds no models, no audio and no I/O - only the rules, so they can be tested
 * at the speed of a hand-moved clock.
 *
 * <p>Not thread-safe by design: it is fed from one place, the event stream of one
 * session, and a lock here would be a claim about a concurrency that does not exist.
 */
public final class NarrationPolicy {

    private final Supplier<Instant> clock;
    private volatile Verbosity verbosity;

    private final List<AgentEvent> window = new ArrayList<>();
    private Instant lastSpoke;
    private ToolClass lastClass;
    private String lastModule;
    private String lastErrorSignature;

    public NarrationPolicy(Verbosity verbosity, Supplier<Instant> clock) {
        this.verbosity = Objects.requireNonNull(verbosity, "verbosity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void verbosity(Verbosity level) {
        this.verbosity = Objects.requireNonNull(level, "level");
    }

    public Verbosity verbosity() {
        return verbosity;
    }

    /**
     * Offers an event to the policy.
     *
     * @return the window to narrate when this event calls for speech, otherwise empty -
     *         the event is remembered either way, so whatever is said next covers it
     */
    public Optional<NarrationRequest> accept(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        Verbosity level = verbosity;

        boolean urgent = event.kind() == EventKind.PERMISSION_REQUEST;
        boolean speaks = urgent || triggers(event, level);

        window.add(event);
        remember(event);

        if (!speaks) {
            return Optional.empty();
        }
        // A permission request ignores the floor. Everything else waits its turn:
        // the user is not blocked on it, and two sentences inside four seconds are
        // heard as one long one anyway.
        if (!urgent && !floorHasPassed(level)) {
            return Optional.empty();
        }
        return Optional.of(handOver(urgent));
    }

    /**
     * Called periodically. Speaks when the ceiling has passed with work pending.
     *
     * @return the window to narrate, or empty when there is nothing to say or the level
     *         has no ceiling at all
     */
    public Optional<NarrationRequest> heartbeat() {
        Duration ceiling = verbosity.ceiling();
        if (ceiling == null || window.isEmpty()) {
            // No ceiling, or nothing has happened. Silence about nothing is correct:
            // a heartbeat that speaks anyway is the chatter the floor exists to stop,
            // arriving through the other door.
            return Optional.empty();
        }
        Instant since = lastSpoke == null ? window.get(0).ts() : lastSpoke;
        if (Duration.between(since, clock.get()).compareTo(ceiling) < 0) {
            return Optional.empty();
        }
        return Optional.of(handOver(false));
    }

    private boolean triggers(AgentEvent event, Verbosity level) {
        return switch (event.kind()) {
            // Always, at every level: the outcome of a run, the end of the work, and
            // the first of a run of errors. These are the things the user asked for.
            case TEST_RESULT, DONE -> true;
            case ERROR -> isNewError(event);
            case TOOL_START -> level != Verbosity.QUIET
                && (isClassChange(event) || isModuleChange(event));
            default -> false;
        };
    }

    /**
     * A run of the same failure speaks once. An agent retrying the same broken command
     * six times is one piece of news, and hearing it six times teaches the user to stop
     * listening.
     */
    private boolean isNewError(AgentEvent event) {
        String signature = event.toolClass() + "|" + event.target();
        return !signature.equals(lastErrorSignature);
    }

    /**
     * A change of tool class - search to read to edit to run - is the shape of progress.
     * The fifteenth {@code Read} in a row is not a change and says nothing new.
     */
    private boolean isClassChange(AgentEvent event) {
        return event.toolClass() != ToolClass.OTHER && event.toolClass() != lastClass;
    }

    /**
     * Moving from one part of the tree to another is worth a word; moving between two
     * files in the same directory is not. The module is taken to be the directory the
     * target sits in, which is what the design's own example - {@code auth/*} to
     * {@code api/*} - describes.
     */
    private boolean isModuleChange(AgentEvent event) {
        String module = moduleOf(event.target());
        return !module.isEmpty() && lastModule != null && !module.equals(lastModule);
    }

    private void remember(AgentEvent event) {
        if (event.kind() == EventKind.ERROR) {
            lastErrorSignature = event.toolClass() + "|" + event.target();
        }
        if (event.kind() == EventKind.TOOL_START) {
            if (event.toolClass() != ToolClass.OTHER) {
                lastClass = event.toolClass();
            }
            String module = moduleOf(event.target());
            if (!module.isEmpty()) {
                lastModule = module;
            }
        }
    }

    private boolean floorHasPassed(Verbosity level) {
        return lastSpoke == null
            || Duration.between(lastSpoke, clock.get()).compareTo(level.floor()) >= 0;
    }

    private NarrationRequest handOver(boolean urgent) {
        NarrationRequest request = new NarrationRequest(List.copyOf(window), urgent);
        window.clear();
        lastSpoke = clock.get();
        return request;
    }

    private static String moduleOf(String target) {
        if (target == null) {
            return "";
        }
        String path = target.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }
}
