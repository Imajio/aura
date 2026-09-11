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

class ClaudeEventParserTest {

    private static final Path FIXTURE = Path.of("..", "testdata", "fixtures",
        "claude-stream-json-tool-call.jsonl");

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    private List<AgentEvent> parseFixture() throws Exception {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        List<AgentEvent> all = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                all.addAll(parser.parseLine(line));
            }
        }
        return all;
    }

    @Test
    void dropsHookNoiseLines() throws Exception {
        List<AgentEvent> events = parseFixture();
        // В фикстуре есть строки system/hook_started и system/hook_response.
        // Ни одна из них не должна дожить до канонического потока.
        assertThat(events)
            .noneMatch(e -> e.raw().contains("\"hook_started\""))
            .noneMatch(e -> e.raw().contains("\"hook_response\""));
    }

    @Test
    void producesSessionStartWithSessionId() throws Exception {
        AgentEvent first = parseFixture().get(0);
        assertThat(first.kind()).isEqualTo(EventKind.SESSION_START);
        assertThat(first.agent()).isEqualTo(Agent.CLAUDE);
        assertThat(first.sessionId()).isNotBlank();
    }

    @Test
    void producesToolStartForBashWithCommandAsTarget() throws Exception {
        AgentEvent start = parseFixture().stream()
            .filter(e -> e.kind() == EventKind.TOOL_START)
            .findFirst()
            .orElseThrow();

        assertThat(start.toolClass()).isEqualTo(ToolClass.EXEC);
        assertThat(start.target()).isEqualTo("echo hi");
        assertThat(start.toolUseId()).startsWith("toolu_");
    }

    @Test
    void toolEndInheritsClassFromMatchingStart() throws Exception {
        AgentEvent end = parseFixture().stream()
            .filter(e -> e.kind() == EventKind.TOOL_END)
            .findFirst()
            .orElseThrow();

        // Класс не написан в строке результата - он берётся из запомненного вызова.
        assertThat(end.toolClass()).isEqualTo(ToolClass.EXEC);
        assertThat(end.ok()).isTrue();
        assertThat(end.summaryHint()).contains("hi");
    }

    @Test
    void finalResultLineBecomesDoneCarryingAnswerText() throws Exception {
        List<AgentEvent> events = parseFixture();
        AgentEvent last = events.get(events.size() - 1);

        assertThat(last.kind()).isEqualTo(EventKind.DONE);
        assertThat(last.ok()).isTrue();
        assertThat(last.summaryHint()).isEqualTo("done");
    }

    @Test
    void aFailedRunIsDoneButNotOk() {
        // What a refused run actually looks like: one turn, no tool calls, and a
        // result line carrying the reason. Reported by the owner after every task
        // finished in under a second - ANTHROPIC_API_KEY was set in the environment
        // Aura passes to the agent, so the CLI billed an account with no credit.
        // Without `ok`, this is indistinguishable from a task that worked.
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"result","subtype":"success","session_id":"s1",
             "result":"Credit balance is too low","is_error":true,"num_turns":1}""");

        assertThat(events).singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo(EventKind.DONE);
                assertThat(e.ok()).isFalse();
                assertThat(e.summaryHint()).isEqualTo("Credit balance is too low");
            });
    }

    @Test
    void toolResultWithErrorBecomesErrorEvent() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"pytest"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_1","content":"boom","is_error":true}]}}""");

        assertThat(events).singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo(EventKind.ERROR);
                assertThat(e.ok()).isFalse();
                assertThat(e.toolClass()).isEqualTo(ToolClass.EXEC);
            });
    }

    @Test
    void taskToolBecomesSubagentStartAndItsChildrenCarryParentId() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");

        List<AgentEvent> started = parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_task","name":"Task","input":{"description":"разбор схемы"}}]}}""");
        assertThat(started).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.SUBAGENT_START));

        List<AgentEvent> nested = parser.parseLine("""
            {"type":"assistant","session_id":"s1","parent_tool_use_id":"toolu_task",
             "message":{"content":[{"type":"text","text":"смотрю таблицы"}]}}""");
        assertThat(nested).singleElement()
            .satisfies(e -> assertThat(e.fromSubagent()).isTrue());

        List<AgentEvent> ended = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_task","content":"готово","is_error":false}]}}""");
        assertThat(ended).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.SUBAGENT_END));
    }

    @Test
    void malformedLineYieldsNoEventsAndDoesNotThrow() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        assertThat(parser.parseLine("это не json")).isEmpty();
        assertThat(parser.parseLine("")).isEmpty();
    }

    @Test
    void successfulTestRunBecomesTestResultRatherThanPlainToolEnd() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_t","name":"Bash","input":{"command":"pytest -q"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_t","content":"4 passed in 0.3s","is_error":false}]},
             "tool_use_result":{"stdout":"4 passed in 0.3s","stderr":""}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TEST_RESULT);
            assertThat(e.ok()).isTrue();
            assertThat(e.summaryHint()).contains("4").contains("passed");
        });
    }

    @Test
    void failingTestRunIsTestResultWithOkFalse() {
        // Form captured from a live claude run: a command exiting non-zero arrives
        // as is_error=true, with the output in the block's content rather than in
        // tool_use_result.stdout.
        // See testdata/fixtures/claude-stream-json-failing-command.jsonl
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_t","name":"Bash","input":{"command":"pytest"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_t",
               "content":"Exit code 1\\n3 passed, 1 failed","is_error":true}]}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TEST_RESULT);
            assertThat(e.ok()).isFalse();
        });
    }

    @Test
    void recordedFailingCommandFixtureYieldsErrorNotSilence() throws Exception {
        Path fixture = Path.of("..", "testdata", "fixtures",
            "claude-stream-json-failing-command.jsonl");
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        List<AgentEvent> all = new ArrayList<>();
        for (String line : Files.readAllLines(fixture, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                all.addAll(parser.parseLine(line));
            }
        }
        assertThat(all).anySatisfy(e -> {
            assertThat(e.toolClass()).isEqualTo(ToolClass.EXEC);
            assertThat(e.ok()).isFalse();
        });
    }

    @Test
    void ordinaryCommandStaysToolEnd() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_g","name":"Bash","input":{"command":"git status"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_g","content":"clean","is_error":false}]}}""");

        assertThat(events).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.TOOL_END));
    }
}
