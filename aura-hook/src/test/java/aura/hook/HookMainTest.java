package aura.hook;

import static org.assertj.core.api.Assertions.assertThat;

import aura.ipc.HookResponse;
import aura.ipc.HookServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookMainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLAUDE_PAYLOAD = """
        {"session_id":"aba8e22f","cwd":"C:\\\\work\\\\backend",
         "tool_name":"Bash","tool_input":{"command":"rm -rf build"}}""";

    @Test
    void wrapsVerdictInTheShapeClaudeExpects(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("deny", "not allowed"))) {
            server.start();

            String out = HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
            JsonNode node = MAPPER.readTree(out);

            assertThat(node.at("/hookSpecificOutput/hookEventName").asText()).isEqualTo("PreToolUse");
            assertThat(node.at("/hookSpecificOutput/permissionDecision").asText()).isEqualTo("deny");
            assertThat(node.at("/hookSpecificOutput/permissionDecisionReason").asText()).isEqualTo("not allowed");
        }
    }

    @Test
    void forwardsToolNameInputAndCwdToAura(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        AtomicReference<String> seenInput = new AtomicReference<>();
        AtomicReference<String> seenCwd = new AtomicReference<>();

        try (HookServer server = new HookServer(socket, req -> {
            seenInput.set(req.toolInputJson());
            seenCwd.set(req.cwd());
            return new HookResponse("allow", "");
        })) {
            server.start();
            HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
        }

        assertThat(seenInput.get()).contains("rm -rf build");
        assertThat(seenCwd.get()).isEqualTo("C:\\work\\backend");
    }

    @Test
    void deniesWhenAuraIsNotListening(@TempDir Path tmp) throws Exception {
        String out = HookMain.decide(CLAUDE_PAYLOAD, tmp.resolve("no.sock"), Duration.ofSeconds(1));
        assertThat(MAPPER.readTree(out).at("/hookSpecificOutput/permissionDecision").asText())
            .isEqualTo("deny");
    }

    @Test
    void deniesOnMalformedStdinPayload(@TempDir Path tmp) throws Exception {
        String out = HookMain.decide("not json", tmp.resolve("no.sock"), Duration.ofSeconds(1));
        assertThat(MAPPER.readTree(out).at("/hookSpecificOutput/permissionDecision").asText())
            .isEqualTo("deny");
    }

    @Test
    void aReplyWithNoVerdictIsPrintedAsARefusal(@TempDir Path tmp) throws Exception {
        // A garbled or truncated server reply parses into a record with null fields.
        // Printing that verbatim would leave the agent with no decision at all.
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse(null, null))) {
            server.start();

            String out = HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
            JsonNode node = MAPPER.readTree(out);

            assertThat(node.at("/hookSpecificOutput/permissionDecision").asText()).isEqualTo("deny");
            assertThat(node.at("/hookSpecificOutput/permissionDecisionReason").asText()).isEmpty();
        }
    }

    @Test
    void anUnrecognisedVerdictIsPrintedAsARefusal(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("maybe", "unsure"))) {
            server.start();

            String out = HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
            assertThat(MAPPER.readTree(out).at("/hookSpecificOutput/permissionDecision").asText())
                .isEqualTo("deny");
        }
    }

    @Test
    void theArgumentWinsOverTheEnvironmentVariable() {
        // The argument is authoritative precisely because env propagation to a hook
        // process is not something we control.
        assertThat(HookMain.socketPath(new String[] {"C:\\from\\argument.sock"}))
            .isEqualTo(Path.of("C:\\from\\argument.sock"));
        // Path.of("   ") itself is rejected by the Windows filesystem provider (a path
        // segment cannot end in whitespace), so the blank case is asserted on the
        // stringified result instead of constructing that invalid Path for comparison.
        assertThat(HookMain.socketPath(new String[] {"   "}).toString()).isNotEqualTo("   ");
        assertThat(HookMain.socketPath(new String[] {})).isNotNull();
    }
}
