package aura.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the temporary settings file passed to the agent via {@code --settings}. The
 * user's global configuration is never touched.
 *
 * <p>The file holds exactly one hook and nothing else: the less it says, the less noise
 * in the event stream and the fewer surprises.
 */
public final class SettingsFileWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsFileWriter() {
    }

    public static Path write(Path targetFile, Path socketPath, Path hookJar, Path javaExe) {
        ObjectNode hookEntry = MAPPER.createObjectNode();
        hookEntry.put("type", "command");
        // The socket path is passed as an argument, not only as an environment variable:
        // whether env from this file reaches the hook process is exactly what RISK-6
        // checks. The argument works regardless of that answer.
        hookEntry.put("command",
            "\"" + javaExe + "\" -jar \"" + hookJar + "\" \"" + socketPath + "\"");

        ArrayNode hookList = MAPPER.createArrayNode();
        hookList.add(hookEntry);

        ObjectNode matcher = MAPPER.createObjectNode();
        matcher.put("matcher", "*");
        matcher.set("hooks", hookList);

        ArrayNode preToolUse = MAPPER.createArrayNode();
        preToolUse.add(matcher);

        ObjectNode hooks = MAPPER.createObjectNode();
        hooks.set("PreToolUse", preToolUse);

        ObjectNode env = MAPPER.createObjectNode();
        env.put("AURA_HOOK_SOCKET", socketPath.toString());

        ObjectNode root = MAPPER.createObjectNode();
        root.set("hooks", hooks);
        root.set("env", env);

        try {
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, root.toPrettyString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not write agent settings: " + targetFile, e);
        }
        return targetFile;
    }
}
