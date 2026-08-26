package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexEventParserTest {

    private static final Path FIXTURE = Path.of("..", "testdata", "fixtures",
        "codex-exec-json-simple.jsonl");

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsRecordedFixtureToSessionTextAndDone() throws Exception {
        CodexEventParser parser = new CodexEventParser(FIXED);
        List<AgentEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                events.addAll(parser.parseLine(line));
            }
        }

        assertThat(events).extracting(AgentEvent::kind).containsExactly(
            EventKind.SESSION_START,
            EventKind.ASSISTANT_TEXT,
            EventKind.DONE);
        assertThat(events).allMatch(e -> e.agent() == Agent.CODEX);
        assertThat(events.get(0).sessionId()).isEqualTo("01a03b0d-4495-71a2-baf0-fb8a51dfddc5");
        assertThat(events.get(1).summaryHint()).isEqualTo("ok");
    }

    @Test
    void commandExecutionBecomesToolStartAndToolEnd() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        List<AgentEvent> started = parser.parseLine("""
            {"type":"item.started","item":{"id":"i1","type":"command_execution","command":"git status"}}""");
        assertThat(started).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TOOL_START);
            assertThat(e.toolClass()).isEqualTo(ToolClass.EXEC);
            assertThat(e.target()).isEqualTo("git status");
        });

        // Команда намеренно не тест-раннер: прогон тестов станет отдельным видом
        // события в Task 5, и этот тест должен остаться про обычный вызов.
        List<AgentEvent> ended = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i1","type":"command_execution",
             "command":"git status","exit_code":0,"aggregated_output":"nothing to commit"}}""");
        assertThat(ended).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TOOL_END);
            assertThat(e.ok()).isTrue();
            assertThat(e.summaryHint()).contains("nothing to commit");
        });
    }

    @Test
    void nonZeroExitCodeBecomesError() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");
        // Команда намеренно не тест-раннер: прогон тестов станет отдельным видом
        // события в Task 5, и этот тест должен остаться про обычный вызов.
        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i9","type":"command_execution",
             "command":"git push","exit_code":1,"aggregated_output":"rejected: non-fast-forward"}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.ERROR);
            assertThat(e.ok()).isFalse();
        });
    }

    @Test
    void unknownItemTypeDegradesToOtherAndDoesNotBreakTheStream() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i2","type":"совершенно_новый_тип"}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.OTHER);
            assertThat(e.toolClass()).isEqualTo(ToolClass.OTHER);
        });
    }

    @Test
    void reasoningItemsAreSkippedEntirely() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");
        assertThat(parser.parseLine("""
            {"type":"item.completed","item":{"id":"i3","type":"reasoning","text":"думаю"}}"""))
            .isEmpty();
    }

    @Test
    void malformedLineYieldsNoEventsAndDoesNotThrow() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        assertThat(parser.parseLine("{неполный")).isEmpty();
    }

    @Test
    void longCommandOutputIsBoundedInTheHint() {
        // Подсказка кормит языковую модель; сырой вывод сборки — это килобайты.
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        String long_ = "x".repeat(500);
        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i1","type":"command_execution",
             "command":"git status","exit_code":0,"aggregated_output":"%s"}}""".formatted(long_));

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.summaryHint()).hasSizeLessThanOrEqualTo(201);
            assertThat(e.summaryHint()).endsWith("…");
        });
    }
}
