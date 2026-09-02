package aura.agents;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a {@code codex exec --json} stream into canonical events.
 *
 * <p>The full set of {@code item.type} values is not known at the time of
 * writing (see RISK-2), so the schema is treated as extensible: an unrecognised
 * type becomes {@link EventKind#OTHER} and does not drop the stream.
 */
public final class CodexEventParser {

    private static final Logger log = LoggerFactory.getLogger(CodexEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private String sessionId = "unknown";

    public CodexEventParser(Clock clock) {
        this.clock = clock;
    }

    public List<AgentEvent> parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonLine);
        } catch (Exception e) {
            log.debug("unrecognised line in the codex stream: {}", SummaryText.abbreviate(jsonLine));
            return List.of();
        }

        String type = root.path("type").asText("");
        return switch (type) {
            case "thread.started" -> {
                sessionId = root.path("thread_id").asText("unknown");
                yield List.of(base(jsonLine).kind(EventKind.SESSION_START).build());
            }
            case "turn.completed" -> List.of(base(jsonLine)
                .kind(EventKind.DONE)
                .ok(true)
                .build());
            case "item.started" -> item(root, jsonLine, true);
            case "item.completed" -> item(root, jsonLine, false);
            default -> List.of();
        };
    }

    private List<AgentEvent> item(JsonNode root, String raw, boolean started) {
        JsonNode item = root.path("item");
        String itemType = item.path("type").asText("");

        if ("reasoning".equals(itemType)) {
            return List.of();
        }
        if ("agent_message".equals(itemType)) {
            return started ? List.of() : List.of(base(raw)
                .kind(EventKind.ASSISTANT_TEXT)
                .summaryHint(SummaryText.abbreviate(item.path("text").asText("")))
                .build());
        }

        ToolClass cls = ToolClass.of(itemType);
        String target = targetOf(item);

        if (cls == ToolClass.OTHER) {
            return started ? List.of() : List.of(base(raw)
                .kind(EventKind.OTHER)
                .target(target)
                .build());
        }

        if (started) {
            return List.of(base(raw)
                .kind(EventKind.TOOL_START)
                .toolClass(cls)
                .target(target)
                .toolUseId(item.path("id").asText(""))
                .summaryHint(target)
                .build());
        }

        boolean ok = !item.hasNonNull("exit_code") || item.get("exit_code").asInt() == 0;
        String output = item.path("aggregated_output").asText("");
        EventKind kind = ok ? EventKind.TOOL_END : EventKind.ERROR;
        String hint = SummaryText.abbreviate(output);

        // Test outcome is its own event kind regardless of exit status: a red run
        // is the case this feature exists for, so the detector must run whether
        // the command exited zero or not.
        if (cls == ToolClass.EXEC) {
            var outcome = TestResultDetector.detect(target, output);
            if (outcome.isPresent()) {
                kind = EventKind.TEST_RESULT;
                ok = outcome.get().ok();
                hint = outcome.get().passed() + " passed, " + outcome.get().failed() + " failed";
            }
        }

        return List.of(base(raw)
            .kind(kind)
            .toolClass(cls)
            .target(target)
            .toolUseId(item.path("id").asText(""))
            .ok(ok)
            .summaryHint(hint)
            .build());
    }

    private static String targetOf(JsonNode item) {
        for (String field : new String[] {"command", "path", "file_path", "query"}) {
            if (item.hasNonNull(field)) {
                return item.get(field).asText();
            }
        }
        return item.path("type").asText("");
    }

    private AgentEvent.Builder base(String raw) {
        return AgentEvent.builder()
            .ts(clock.instant())
            .sessionId(sessionId)
            .agent(Agent.CODEX)
            .toolClass(ToolClass.OTHER)
            .target("")
            .raw(raw);
    }
}
