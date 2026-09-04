package aura.core;

import java.time.Duration;

/**
 * How talkative the narrator is, and everything that follows from it.
 *
 * <p>Three positions, reachable from the tray and later by voice. Each carries its own
 * floor and ceiling because they are the same decision seen twice: a level that speaks
 * about more things has to speak about them sooner, or the window it describes is
 * already stale by the time it is read out.
 *
 * <p><b>The floor stops it chattering. The ceiling stops it going silent.</b> Without a
 * floor a burst of tool calls produces a burst of speech nobody can think next to;
 * without a ceiling a long quiet stretch is indistinguishable from an agent that died.
 */
public enum Verbosity {

    /**
     * Permissions, the first error, test results, completion — and nothing else.
     * The ceiling is off: an application asked to be quiet stays quiet, even when
     * that means saying nothing for ten minutes.
     */
    QUIET(Duration.ofSeconds(8), null, true),

    /** Adds a change of tool class and a change of module. */
    NORMAL(Duration.ofSeconds(4), Duration.ofSeconds(25), false),

    /** Adds batches of like tool calls and the assistant's own text, abbreviated. */
    VERBOSE(Duration.ofMillis(2500), Duration.ofSeconds(12), false);

    private final Duration floor;
    private final Duration ceiling;
    private final boolean smallModel;

    Verbosity(Duration floor, Duration ceiling, boolean smallModel) {
        this.floor = floor;
        this.ceiling = ceiling;
        this.smallModel = smallModel;
    }

    /** The shortest gap allowed between two spoken lines. */
    public Duration floor() {
        return floor;
    }

    /** The longest silence allowed while work is pending, or null when there is none. */
    public Duration ceiling() {
        return ceiling;
    }

    /**
     * Whether this level narrates with the smaller model. A quiet narrator says less
     * and says it about simpler things, so it does not need the larger one — which is
     * also what makes {@link #quieter()} a workable answer to running on battery.
     */
    public boolean smallModel() {
        return smallModel;
    }

    /**
     * One position down, for running on battery.
     *
     * <p>Stops at {@link #QUIET} rather than wrapping round to silence: unplugging a
     * laptop should cost the user some commentary, never the permission questions that
     * are the only thing standing between an agent and their filesystem.
     */
    public Verbosity quieter() {
        return switch (this) {
            case VERBOSE -> NORMAL;
            case NORMAL, QUIET -> QUIET;
        };
    }
}
