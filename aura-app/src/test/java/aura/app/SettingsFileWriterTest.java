package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileWriterTest {

    @Test
    void writesPreToolUseHookPointingAtOurJar(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("aura-settings.json");
        Path jar = tmp.resolve("aura-hook.jar");
        Path java = Path.of("C:", "jdk", "bin", "java.exe");
        Path socket = tmp.resolve("aura.sock");

        SettingsFileWriter.write(settings, socket, jar, java);

        JsonNode root = new ObjectMapper()
            .readTree(Files.readString(settings, StandardCharsets.UTF_8));
        JsonNode matcher = root.at("/hooks/PreToolUse/0");

        assertThat(matcher.path("matcher").asText()).isEqualTo("*");
        String command = matcher.at("/hooks/0/command").asText();
        assertThat(command)
            .contains(java.toString())
            .contains(jar.toString())
            .contains(socket.toString());
        assertThat(matcher.at("/hooks/0/type").asText()).isEqualTo("command");
        assertThat(root.at("/env/AURA_HOOK_SOCKET").asText()).isEqualTo(socket.toString());
    }

    @Test
    void neverEmitsBypassPermissions(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("aura-settings.json");
        SettingsFileWriter.write(settings, tmp.resolve("s.sock"),
            tmp.resolve("h.jar"), Path.of("java"));

        assertThat(Files.readString(settings, StandardCharsets.UTF_8))
            .doesNotContain("bypassPermissions");
    }
}
