package aura.agents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A fake {@code codex exec}: reads the prompt from stdin, prints a stream of
 * JSONL, and exits. Also prints the received arguments as part of the
 * message text, so the test can check for the presence of {@code resume}.
 */
public final class FakeCodexMain {

    public static void main(String[] args) throws Exception {
        List<String> arguments = Arrays.asList(args);
        // A real codex emits UTF-8 JSON regardless of the host platform's
        // console codepage; pin this fake's stdout the same way, or a
        // Cyrillic prompt would round-trip through Windows' non-UTF-8
        // default console encoding as '?'.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder prompt = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            prompt.append(line);
        }

        System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"t-fake\"}");
        System.out.println("{\"type\":\"turn.started\"}");
        System.out.println("{\"type\":\"item.completed\",\"item\":{\"id\":\"i0\","
            + "\"type\":\"agent_message\",\"text\":\"args=" + String.join(" ", arguments)
            + " prompt=" + prompt.toString().replace('"', '\'') + "\"}}");
        System.out.println("{\"type\":\"turn.completed\",\"usage\":{}}");
        System.out.flush();
    }

    private FakeCodexMain() {
    }
}
