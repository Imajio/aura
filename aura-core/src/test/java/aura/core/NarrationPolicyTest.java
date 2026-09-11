package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * When the narrator opens its mouth, and - more importantly - when it does not.
 *
 * <p>Both failures are user-visible and neither is a crash: a policy that fires too
 * often produces an application nobody can think next to, and one that fires too
 * rarely produces an agent that appears to have died.
 */
class NarrationPolicyTest {

    /** A clock the test moves by hand; the policy is all about elapsed time. */
    private static final class TestClock {
        private Instant now = Instant.parse("2026-09-04T10:00:00Z");

        Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final TestClock clock = new TestClock();

    private NarrationPolicy policy(Verbosity verbosity) {
        return new NarrationPolicy(verbosity, clock::instant);
    }

    private AgentEvent event(EventKind kind) {
        return event(kind, ToolClass.OTHER, "");
    }

    private AgentEvent event(EventKind kind, ToolClass toolClass, String target) {
        return AgentEvent.builder()
            .ts(clock.instant())
            .sessionId("s1")
            .agent(Agent.CLAUDE)
            .kind(kind)
            .toolClass(toolClass)
            .target(target)
            .summaryHint(kind + " " + target)
            .build();
    }

    // --- what always speaks -------------------------------------------------

    @Test
    void aTestResultAlwaysNarrates() {
        assertThat(policy(Verbosity.QUIET).accept(event(EventKind.TEST_RESULT))).isPresent();
    }

    @Test
    void completionAlwaysNarrates() {
        assertThat(policy(Verbosity.QUIET).accept(event(EventKind.DONE))).isPresent();
    }

    @Test
    void aPermissionRequestNarratesImmediatelyEvenInsideTheFloor() {
        // The one case where waiting is not an option: the agent is blocked until
        // the user answers, and the user cannot answer what they were not told.
        NarrationPolicy policy = policy(Verbosity.QUIET);
        assertThat(policy.accept(event(EventKind.TEST_RESULT))).isPresent();

        Optional<NarrationRequest> urgent = policy.accept(event(EventKind.PERMISSION_REQUEST));

        assertThat(urgent).isPresent();
        assertThat(urgent.get().urgent()).isTrue();
    }

    // --- what stays quiet ---------------------------------------------------

    @Test
    void theFirstToolCallIsAChangeOfStateAndSpeaks() {
        // Going from "nothing yet" to "reading files" is the moment the product
        // scenario calls "Claude got to work". It is the change every later Read
        // is measured against.
        assertThat(policy(Verbosity.NORMAL)
            .accept(event(EventKind.TOOL_START, ToolClass.READ, "a.txt"))).isPresent();
    }

    @Test
    void aSecondToolCallOfTheSameClassSaysNothingNew() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TOOL_START, ToolClass.READ, "a.txt"));

        clock.advance(Duration.ofSeconds(10));

        assertThat(policy.accept(event(EventKind.TOOL_START, ToolClass.READ, "b.txt"))).isEmpty();
    }

    @Test
    void fifteenReadsInARowAreOneChangeOfClassAndOneNarration() {
        // The rule the design states outright: a change of class speaks, the
        // fifteenth Read in a row does not.
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        int narrations = 0;
        for (int i = 0; i < 15; i++) {
            clock.advance(Duration.ofSeconds(5));
            if (policy.accept(event(EventKind.TOOL_START, ToolClass.READ, "f" + i)).isPresent()) {
                narrations++;
            }
        }
        assertThat(narrations).isEqualTo(1);
    }

    @Test
    void theSameErrorRepeatedIsNarratedOnce() {
        NarrationPolicy policy = policy(Verbosity.QUIET);
        AgentEvent failure = event(EventKind.ERROR, ToolClass.EXEC, "npm test");

        assertThat(policy.accept(failure)).isPresent();
        clock.advance(Duration.ofMinutes(1));
        assertThat(policy.accept(failure)).isEmpty();
    }

    @Test
    void aDifferentErrorIsNarratedAgain() {
        NarrationPolicy policy = policy(Verbosity.QUIET);
        assertThat(policy.accept(event(EventKind.ERROR, ToolClass.EXEC, "npm test"))).isPresent();
        clock.advance(Duration.ofMinutes(1));

        assertThat(policy.accept(event(EventKind.ERROR, ToolClass.EXEC, "npm build"))).isPresent();
    }

    // --- the floor ----------------------------------------------------------

    @Test
    void twoTriggersInsideTheFloorProduceOneNarration() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        assertThat(policy.accept(event(EventKind.TEST_RESULT))).isPresent();

        clock.advance(Duration.ofSeconds(1));

        assertThat(policy.accept(event(EventKind.TEST_RESULT))).isEmpty();
    }

    @Test
    void aTriggerAfterTheFloorNarratesAgain() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TEST_RESULT));

        clock.advance(Duration.ofSeconds(5));  // normal floor is 4 s

        assertThat(policy.accept(event(EventKind.TEST_RESULT))).isPresent();
    }

    @Test
    void quietWaitsLongerThanNormal() {
        assertThat(Verbosity.QUIET.floor()).isEqualTo(Duration.ofSeconds(8));
        assertThat(Verbosity.NORMAL.floor()).isEqualTo(Duration.ofSeconds(4));
        assertThat(Verbosity.VERBOSE.floor()).isEqualTo(Duration.ofMillis(2500));
    }

    // --- the ceiling --------------------------------------------------------

    @Test
    void silenceWithWorkPendingEventuallyBreaksItself() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TOOL_END, ToolClass.READ, "a.txt"));

        clock.advance(Duration.ofSeconds(30));  // normal ceiling is 25 s

        assertThat(policy.heartbeat()).isPresent();
    }

    @Test
    void theHeartbeatStaysQuietWhenNothingHasHappened() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);

        clock.advance(Duration.ofMinutes(5));

        assertThat(policy.heartbeat()).isEmpty();
    }

    @Test
    void quietHasNoHeartbeatAtAll() {
        // Stated in the design: at the quiet level the ceiling is off. An
        // application asked to be quiet stays quiet.
        NarrationPolicy policy = policy(Verbosity.QUIET);
        policy.accept(event(EventKind.TOOL_END, ToolClass.READ, "a.txt"));

        clock.advance(Duration.ofMinutes(10));

        assertThat(policy.heartbeat()).isEmpty();
    }

    // --- what is handed to the narrator -------------------------------------

    @Test
    void theWindowCarriesEverythingSinceTheLastNarration() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        // Kinds that never trigger on their own, so the window is still filling.
        policy.accept(event(EventKind.TOOL_END, ToolClass.READ, "a.txt"));
        policy.accept(event(EventKind.ASSISTANT_TEXT));

        NarrationRequest request = policy.accept(event(EventKind.TEST_RESULT)).orElseThrow();

        assertThat(request.events()).hasSize(3);
        assertThat(request.events().get(0).toolClass()).isEqualTo(ToolClass.READ);
    }

    @Test
    void theWindowIsEmptiedOnceItHasBeenHandedOver() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TOOL_END, ToolClass.READ, "a.txt"));
        policy.accept(event(EventKind.TEST_RESULT));

        clock.advance(Duration.ofSeconds(5));
        NarrationRequest second = policy.accept(event(EventKind.TEST_RESULT)).orElseThrow();

        assertThat(second.events()).hasSize(1);
    }

    // --- verbosity ----------------------------------------------------------

    @Test
    void aChangeOfToolClassSpeaksAtNormalButNotAtQuiet() {
        NarrationPolicy quiet = policy(Verbosity.QUIET);
        quiet.accept(event(EventKind.TOOL_START, ToolClass.READ, "a.txt"));
        clock.advance(Duration.ofSeconds(10));
        assertThat(quiet.accept(event(EventKind.TOOL_START, ToolClass.EXEC, "npm test"))).isEmpty();

        NarrationPolicy normal = policy(Verbosity.NORMAL);
        normal.accept(event(EventKind.TOOL_START, ToolClass.READ, "a.txt"));
        clock.advance(Duration.ofSeconds(10));
        assertThat(normal.accept(event(EventKind.TOOL_START, ToolClass.EXEC, "npm test")))
            .isPresent();
    }

    @Test
    void aChangeOfModuleSpeaksAtNormal() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TOOL_START, ToolClass.EDIT, "auth/login.java"));
        clock.advance(Duration.ofSeconds(10));

        assertThat(policy.accept(event(EventKind.TOOL_START, ToolClass.EDIT, "api/users.java")))
            .isPresent();
    }

    @Test
    void stayingInsideOneModuleDoesNotSpeak() {
        NarrationPolicy policy = policy(Verbosity.NORMAL);
        policy.accept(event(EventKind.TOOL_START, ToolClass.EDIT, "auth/login.java"));
        clock.advance(Duration.ofSeconds(10));

        assertThat(policy.accept(event(EventKind.TOOL_START, ToolClass.EDIT, "auth/token.java")))
            .isEmpty();
    }

    @Test
    void batteryShiftsOneLevelDownAndStopsAtQuiet() {
        assertThat(Verbosity.VERBOSE.quieter()).isEqualTo(Verbosity.NORMAL);
        assertThat(Verbosity.NORMAL.quieter()).isEqualTo(Verbosity.QUIET);
        assertThat(Verbosity.QUIET.quieter()).isEqualTo(Verbosity.QUIET);
    }

    @Test
    void quietNarratesWithTheSmallerModel() {
        // The registry's battery fallback, stated where the level is chosen: a
        // quiet narrator has less to say and does not need the larger model.
        assertThat(Verbosity.QUIET.smallModel()).isTrue();
        assertThat(Verbosity.NORMAL.smallModel()).isFalse();
        assertThat(Verbosity.VERBOSE.smallModel()).isFalse();
    }
}
