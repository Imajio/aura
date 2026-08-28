package aura.hook;

import aura.ipc.HookClient;
import aura.ipc.HookRequest;
import aura.ipc.HookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The {@code PreToolUse} hook that Claude Code runs before every tool call. Reads the
 * description of the call from stdin, asks Aura over the socket, and prints the
 * verdict to stdout.
 *
 * <p>The process is short-lived and must fail fast toward a refusal: if Aura does not
 * answer, the only acceptable response is deny.
 */
public final class HookMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    public static void main(String[] args) throws Exception {
        String stdin = new String(readAll(System.in), StandardCharsets.UTF_8);
        System.out.println(decide(stdin, socketPath(args), DEFAULT_TIMEOUT));
    }

    /** The argument wins over the environment variable: it does not depend on whether the agent propagates it to the hook. */
    static Path socketPath(String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String fromEnv = System.getenv("AURA_HOOK_SOCKET");
        return Path.of(fromEnv == null ? "no-socket" : fromEnv);
    }

    /** Split out of {@link #main} so the logic can be tested without spawning a process. */
    public static String decide(String stdinJson, Path socketPath, Duration timeout) {
        HookResponse response;
        try {
            JsonNode payload = MAPPER.readTree(stdinJson);
            HookRequest request = new HookRequest(
                payload.path("session_id").asText(""),
                payload.path("tool_name").asText(""),
                payload.path("tool_input").toString(),
                payload.path("cwd").asText(""));
            response = HookClient.ask(socketPath, request, timeout);
        } catch (Exception e) {
            response = HookResponse.deny("hook could not parse the request");
        }
        return render(response);
    }

    private static String render(HookResponse response) {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("hookEventName", "PreToolUse");
        inner.put("permissionDecision", response.permissionDecision());
        inner.put("permissionDecisionReason", response.reason() == null ? "" : response.reason());

        ObjectNode root = MAPPER.createObjectNode();
        root.set("hookSpecificOutput", inner);
        return root.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        return in.readAllBytes();
    }

    private HookMain() {
    }
}
