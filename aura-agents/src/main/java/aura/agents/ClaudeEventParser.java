package aura.agents;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает поток {@code claude -p --output-format stream-json} в канонические
 * события. Хранит состояние: строка результата инструмента не содержит его имени,
 * поэтому класс и цель берутся из запомненного вызова. Один экземпляр на сессию.
 */
public final class ClaudeEventParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record PendingTool(ToolClass toolClass, String target, boolean subagent) {}

    private final Clock clock;
    private final Map<String, PendingTool> pending = new HashMap<>();
    private String sessionId = "unknown";

    public ClaudeEventParser(Clock clock) {
        this.clock = clock;
    }

    /** Возвращает от нуля до нескольких событий. Никогда не бросает. */
    public List<AgentEvent> parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonLine);
        } catch (Exception e) {
            log.debug("нераспознанная строка потока claude: {}", SummaryText.abbreviate(jsonLine));
            return List.of();
        }

        String type = root.path("type").asText("");
        if (root.hasNonNull("session_id")) {
            sessionId = root.get("session_id").asText();
        }

        return switch (type) {
            case "system" -> parseSystem(root, jsonLine);
            case "assistant" -> parseAssistant(root, jsonLine);
            case "user" -> parseUser(root, jsonLine);
            case "result" -> List.of(parseResult(root, jsonLine));
            default -> List.of();
        };
    }

    private List<AgentEvent> parseSystem(JsonNode root, String raw) {
        String subtype = root.path("subtype").asText("");
        // Строки hook_started и hook_response — побочный шум от пользовательских
        // хуков. Они не относятся к работе агента и не должны попадать в поток.
        if (subtype.startsWith("hook")) {
            return List.of();
        }
        if ("init".equals(subtype)) {
            return List.of(base(root, raw)
                .kind(EventKind.SESSION_START)
                .target(root.path("cwd").asText(""))
                .build());
        }
        return List.of();
    }

    private List<AgentEvent> parseAssistant(JsonNode root, String raw) {
        List<AgentEvent> out = new ArrayList<>();
        for (JsonNode block : root.path("message").path("content")) {
            String blockType = block.path("type").asText("");
            if ("text".equals(blockType)) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    out.add(base(root, raw)
                        .kind(EventKind.ASSISTANT_TEXT)
                        .toolClass(ToolClass.OTHER)
                        .target("")
                        .summaryHint(SummaryText.abbreviate(text))
                        .build());
                }
            } else if ("tool_use".equals(blockType)) {
                out.add(toolStart(root, raw, block));
            }
        }
        return out;
    }

    private AgentEvent toolStart(JsonNode root, String raw, JsonNode block) {
        String name = block.path("name").asText("");
        String id = block.path("id").asText("");
        ToolClass cls = ToolClass.of(name);
        String target = targetOf(name, block.path("input"));
        boolean subagent = cls == ToolClass.TASK;

        pending.put(id, new PendingTool(cls, target, subagent));

        return base(root, raw)
            .kind(subagent ? EventKind.SUBAGENT_START : EventKind.TOOL_START)
            .toolClass(cls)
            .target(target)
            .toolUseId(id)
            .summaryHint(target)
            .build();
    }

    private List<AgentEvent> parseUser(JsonNode root, String raw) {
        List<AgentEvent> out = new ArrayList<>();
        for (JsonNode block : root.path("message").path("content")) {
            if (!"tool_result".equals(block.path("type").asText(""))) {
                continue;
            }
            String id = block.path("tool_use_id").asText("");
            boolean isError = block.path("is_error").asBoolean(false);
            PendingTool tool = pending.remove(id);
            ToolClass cls = tool == null ? ToolClass.OTHER : tool.toolClass();
            String target = tool == null ? "" : tool.target();
            boolean subagent = tool != null && tool.subagent();

            EventKind kind = isError ? EventKind.ERROR
                : subagent ? EventKind.SUBAGENT_END
                : EventKind.TOOL_END;

            out.add(base(root, raw)
                .kind(kind)
                .toolClass(cls)
                .target(target)
                .toolUseId(id)
                .ok(!isError)
                .summaryHint(SummaryText.abbreviate(resultText(root, block)))
                .build());
        }
        return out;
    }

    private AgentEvent parseResult(JsonNode root, String raw) {
        boolean isError = root.path("is_error").asBoolean(false);
        return base(root, raw)
            .kind(EventKind.DONE)
            .toolClass(ToolClass.OTHER)
            .target("")
            .ok(!isError)
            .summaryHint(root.path("result").asText(""))
            .build();
    }

    /** Что именно произносить о вызове: команда, путь или шаблон. */
    private static String targetOf(String toolName, JsonNode input) {
        for (String field : new String[] {"command", "file_path", "path", "pattern", "url", "description"}) {
            if (input.hasNonNull(field)) {
                return input.get(field).asText();
            }
        }
        return toolName;
    }

    private static String resultText(JsonNode root, JsonNode block) {
        JsonNode stdout = root.path("tool_use_result").path("stdout");
        if (!stdout.isMissingNode() && !stdout.asText("").isBlank()) {
            return stdout.asText();
        }
        JsonNode content = block.path("content");
        return content.isTextual() ? content.asText() : content.toString();
    }

    private AgentEvent.Builder base(JsonNode root, String raw) {
        return AgentEvent.builder()
            .ts(timestampOf(root))
            .sessionId(sessionId)
            .agent(Agent.CLAUDE)
            .toolClass(ToolClass.OTHER)
            .target("")
            .parentToolUseId(root.hasNonNull("parent_tool_use_id")
                ? root.get("parent_tool_use_id").asText() : null)
            .raw(raw);
    }

    private Instant timestampOf(JsonNode root) {
        if (root.hasNonNull("timestamp")) {
            try {
                return Instant.parse(root.get("timestamp").asText());
            } catch (DateTimeParseException ignored) {
                // поток не обязан приносить время в каждой строке
            }
        }
        return clock.instant();
    }

}
