package aura.agents;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Выводит результат прогона тестов из команды и её вывода.
 *
 * <p>Два условия должны выполниться одновременно: команда похожа на тест-раннер
 * и вывод похож на сводку. Одного вывода недостаточно — иначе {@code echo "4 passed"}
 * превратится в бодрый доклад о зелёных тестах.
 */
public final class TestResultDetector {

    public record Outcome(int passed, int failed, boolean ok) {}

    private static final List<Pattern> RUNNERS = List.of(
        Pattern.compile("(^|[\\\\/\\s])pytest\\b"),
        Pattern.compile("\\bmvn\\b.*\\b(test|verify)\\b"),
        Pattern.compile("\\bgradlew?\\b.*\\btest\\b"),
        Pattern.compile("\\bnpm\\b\\s+(run\\s+)?test\\b"),
        Pattern.compile("\\b(jest|vitest)\\b"),
        Pattern.compile("\\bgo\\s+test\\b"),
        Pattern.compile("\\bcargo\\s+test\\b")
    );

    private static final Pattern PYTEST =
        Pattern.compile("(?:(\\d+)\\s+passed)?(?:,?\\s*(\\d+)\\s+failed)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUREFIRE = Pattern.compile(
        "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JEST = Pattern.compile(
        "Tests:\\s*(?:(\\d+)\\s+failed,\\s*)?(\\d+)\\s+passed", Pattern.CASE_INSENSITIVE);

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

        Matcher surefire = SUREFIRE.matcher(output);
        if (surefire.find()) {
            int run = Integer.parseInt(surefire.group(1));
            int failed = Integer.parseInt(surefire.group(2)) + Integer.parseInt(surefire.group(3));
            return Optional.of(new Outcome(run - failed, failed, failed == 0));
        }

        Matcher jest = JEST.matcher(output);
        if (jest.find()) {
            int failed = jest.group(1) == null ? 0 : Integer.parseInt(jest.group(1));
            int passed = Integer.parseInt(jest.group(2));
            return Optional.of(new Outcome(passed, failed, failed == 0));
        }

        Matcher pytest = PYTEST.matcher(output);
        while (pytest.find()) {
            String passedGroup = pytest.group(1);
            String failedGroup = pytest.group(2);
            if (passedGroup == null && failedGroup == null) {
                continue;
            }
            int passed = passedGroup == null ? 0 : Integer.parseInt(passedGroup);
            int failed = failedGroup == null ? 0 : Integer.parseInt(failedGroup);
            return Optional.of(new Outcome(passed, failed, failed == 0));
        }

        return Optional.empty();
    }
}
