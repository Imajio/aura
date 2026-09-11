package aura.agents;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a test run's outcome from a command and its output.
 *
 * <p>Two conditions must hold at once: the command looks like a test runner
 * and the output looks like a summary. Output alone is not enough - otherwise
 * {@code echo "4 passed"} would turn into a cheerful report of green tests.
 */
public final class TestResultDetector {

    public record Outcome(int passed, int failed, boolean ok) {}

    private static final List<Pattern> RUNNERS = List.of(
        Pattern.compile("(^|[\\\\/\\s])pytest\\b"),
        Pattern.compile("\\bmvnw?\\b.*\\b(test|verify)\\b"),
        Pattern.compile("\\bgradlew?\\b.*\\btest\\b"),
        Pattern.compile("\\b(npm|yarn|pnpm)\\b\\s+(run\\s+)?test\\b"),
        Pattern.compile("\\b(jest|vitest)\\b"),
        Pattern.compile("\\bgo\\s+test\\b"),
        Pattern.compile("\\bcargo\\s+test\\b")
    );

    private static final Pattern SUREFIRE = Pattern.compile(
        "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSED = Pattern.compile("\\b(\\d+)\\s+passed\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED = Pattern.compile("\\b(\\d+)\\s+failed\\b", Pattern.CASE_INSENSITIVE);

    private TestResultDetector() {
    }

    public static Optional<Outcome> detect(String command, String output) {
        if (command == null || output == null || output.isBlank()) {
            return Optional.empty();
        }
        String cmd = command.toLowerCase(Locale.ROOT);
        boolean isRunner = RUNNERS.stream().anyMatch(p -> p.matcher(cmd).find());
        if (!isRunner) {
            return Optional.empty();
        }

        // Last match, not the first: with several test classes Maven prints a
        // per-class summary before the build total. Taking the first one declares
        // a red build green.
        Matcher surefire = SUREFIRE.matcher(output);
        int[] last = null;
        while (surefire.find()) {
            last = new int[] {
                Integer.parseInt(surefire.group(1)),
                Integer.parseInt(surefire.group(2)) + Integer.parseInt(surefire.group(3))
            };
        }
        if (last != null) {
            return Optional.of(new Outcome(last[0] - last[1], last[1], last[1] == 0));
        }

        Integer passed = lastCount(PASSED, output);
        Integer failed = lastCount(FAILED, output);
        if (passed == null && failed == null) {
            return Optional.empty();
        }
        int p = passed == null ? 0 : passed;
        int f = failed == null ? 0 : failed;
        return Optional.of(new Outcome(p, f, f == 0));
    }

    /** Last match: the summary sits at the end of the output, not the start. */
    private static Integer lastCount(Pattern pattern, String output) {
        Matcher m = pattern.matcher(output);
        Integer value = null;
        while (m.find()) {
            value = Integer.parseInt(m.group(1));
        }
        return value;
    }
}
