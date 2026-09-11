package aura.agents;

/**
 * A fake agent that fails the way the real one does: a line on stderr and an
 * immediate non-zero exit, with nothing on stdout at all.
 *
 * <p>This is not hypothetical. {@code claude} refuses a session id it has seen
 * before - "Session ID … is already in use" - and dies exactly like this. The
 * application showed nothing, because the reason was written to a stream that
 * was being logged below the level anyone reads.
 */
public final class DyingAgentMain {

    public static void main(String[] args) {
        System.err.println(args.length > 0 ? args[0] : "something went wrong");
        System.err.flush();
        System.exit(1);
    }

    private DyingAgentMain() {
    }
}
