package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestResultDetectorTest {

    @Test
    void detectsPytestGreen() {
        var outcome = TestResultDetector.detect("pytest -q tests/", "4 passed in 0.31s").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(4);
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.ok()).isTrue();
    }

    @Test
    void detectsPytestRed() {
        var outcome = TestResultDetector
            .detect("pytest tests/test_auth.py", "3 passed, 1 failed in 1.02s").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(3);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void detectsMavenSurefireSummary() {
        var outcome = TestResultDetector.detect("mvn -q test",
            "Tests run: 12, Failures: 2, Errors: 1, Skipped: 0").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(9);
        assertThat(outcome.failed()).isEqualTo(3);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void detectsJestSummary() {
        var outcome = TestResultDetector.detect("npm test",
            "Tests:       2 failed, 18 passed, 20 total").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(18);
        assertThat(outcome.failed()).isEqualTo(2);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void ignoresCommandsThatAreNotTestRunners() {
        assertThat(TestResultDetector.detect("git status", "4 passed")).isEmpty();
        assertThat(TestResultDetector.detect("echo 12 passed", "12 passed")).isEmpty();
    }

    @Test
    void testRunnerWithUnrecognisedOutputYieldsNothing() {
        assertThat(TestResultDetector.detect("pytest", "collecting ...")).isEmpty();
    }

    @Test
    void handlesNullsWithoutThrowing() {
        assertThat(TestResultDetector.detect(null, null)).isEmpty();
        assertThat(TestResultDetector.detect("pytest", null)).isEmpty();
    }
}
