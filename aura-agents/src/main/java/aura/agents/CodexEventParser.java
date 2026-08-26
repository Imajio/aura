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
 * Превращает поток {@code codex exec --json} в канонические события.
 *
 * <p>Полный перечень значений {@code item.type} на момент написания неизвестен
 * (см. RISK-2), поэтому схема расширяемая: незнакомый тип становится
 * {@link EventKind#OTHER} и не роняет поток.
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
            log.debug("нераспознанная строка потока codex: {}", SummaryText.abbreviate(jsonLine));
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
        return List.of(base(raw)
            .kind(ok ? EventKind.TOOL_END : EventKind.ERROR)
            .toolClass(cls)
            .target(target)
            .toolUseId(item.path("id").asText(""))
            .ok(ok)
            .summaryHint(SummaryText.abbreviate(item.path("aggregated_output").asText("")))
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
