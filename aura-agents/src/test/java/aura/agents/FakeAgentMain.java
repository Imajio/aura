package aura.agents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A fake agent for tests. Behaves like {@code claude -p --input-format
 * stream-json --output-format stream-json}: reads one JSON line at a time
 * from stdin and emits a short stream of events for each.
 *
 * <p>Exists so that tests of the process mechanics do not hit the network or
 * spend tokens. Lives in test sources and never ships.
 */
public final class FakeAgentMain {

    public static void main(String[] args) throws Exception {
        String sessionId = args.length > 0 ? args[0] : "fake-session";

        System.out.println("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\""
            + sessionId + "\",\"cwd\":\"C:/fake\"}");
        System.out.flush();

        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        int turn = 0;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            if (line.contains("\"СТОП\"")) {
                break;
            }
            // The tool-use id is unique per turn. Reusing the same id across
            // every turn would hide a collision in the parser's pending-call
            // table: the test would pass by accident.
            String toolUseId = "toolu_fake_" + (++turn);

            System.out.println("{\"type\":\"assistant\",\"session_id\":\"" + sessionId
                + "\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"" + toolUseId
                + "\",\"name\":\"Bash\",\"input\":{\"command\":\"echo hi\"}}]}}");
            System.out.println("{\"type\":\"user\",\"session_id\":\"" + sessionId
                + "\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"" + toolUseId + "\",\"content\":\"hi\",\"is_error\":false}]},"
                + "\"tool_use_result\":{\"stdout\":\"hi\",\"stderr\":\"\"}}");
            System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\""
                + sessionId + "\",\"result\":\"done\",\"is_error\":false}");
            System.out.flush();
        }
    }

    private FakeAgentMain() {
    }
}
