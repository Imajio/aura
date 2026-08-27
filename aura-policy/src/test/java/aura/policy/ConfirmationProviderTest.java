package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConfirmationProviderTest {

    private static final ToolRequest REQUEST =
        new ToolRequest("Bash", "{\"command\":\"rm -rf build\"}", Path.of("C:", "work"));

    @Test
    void explicitAllowPassesThrough() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.ALLOW);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.ALLOW);
    }

    @Test
    void explicitDenyStaysDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.DENY);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void confirmAsAnAnswerIsTreatedAsDeny() {
        // CONFIRM is a question, not an answer. An implementation that returns it is buggy.
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.CONFIRM);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void nullAnswerIsTreatedAsDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> null);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void thrownExceptionIsTreatedAsDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> {
            throw new IllegalStateException("dialog did not open");
        });
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void slowAnswerIsCutOffByTimeoutAndBecomesDeny() {
        AtomicBoolean finished = new AtomicBoolean(false);
        // Zero grace: this test checks the cutoff itself, not the behavior of a dialog
        // that closes itself.
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Decision.DENY;
            }
            finished.set(true);
            return Decision.ALLOW;
        }, Duration.ZERO);

        long started = System.nanoTime();
        Decision decision = guarded.confirm(REQUEST, Duration.ofMillis(200));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(decision).isEqualTo(Decision.DENY);
        assertThat(elapsedMs).isLessThan(1500);
        assertThat(finished).isFalse();
    }
}
