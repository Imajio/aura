package aura.agents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A fake {@code codex exec}: reads the prompt from stdin, prints a stream of
 * JSONL, and exits. Also prints the received arguments as part of the
 * message text, so the test can check for the presence of {@code resume}.
 *
 * <p>Two extra modes exist only to test that {@link CodexSession#close()}
 * kills a process tree, not just its root: {@code --child} sleeps forever
 * and does nothing else, standing in for a grandchild of the session (a
 * {@code pytest} or {@code npm} started by the real agent). {@code --hang}
 * spawns one such child, reports both pids, and then also sleeps forever
 * instead of returning - so a test can confirm both are alive, close the
 * session, and confirm both are gone.
 */
public final class FakeCodexMain {

    public static void main(String[] args) throws Exception {
        List<String> arguments = Arrays.asList(args);
        // A real codex emits UTF-8 JSON regardless of the host platform's
        // console codepage; pin this fake's stdout the same way, or a
        // Cyrillic prompt would round-trip through Windows' non-UTF-8
        // default console encoding as '?'.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        if (arguments.contains("--child")) {
            // A grandchild of the session: exists only to be found and killed.
            Thread.sleep(Long.MAX_VALUE);
            return;
        }

        if (arguments.contains("--hang")) {
            Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "aura.agents.FakeCodexMain", "--child")
                .start();

            System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"t-hang\"}");
            System.out.println("{\"type\":\"item.completed\",\"item\":{\"id\":\"i0\","
                + "\"type\":\"agent_message\",\"text\":\"pids=" + ProcessHandle.current().pid()
                + "," + child.pid() + "\"}}");
            System.out.flush();
            // Deliberately never prints turn.completed: nothing may await DONE
            // and skip past the point where the test checks both pids are alive.
            Thread.sleep(Long.MAX_VALUE);
            return;
        }

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
