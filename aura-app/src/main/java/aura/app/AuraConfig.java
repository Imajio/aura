package aura.app;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Application settings. In M1 there are few of them: where the executables live, where
 * the project registry is, and what the timeouts are.
 */
public record AuraConfig(
    Path claudeExe,
    Path codexExe,
    Path projectsFile,
    Path hookJar,
    Path javaExe,
    Path runDir,
    Duration idleTimeout,
    Duration confirmTimeout,
    Path sidecarDir,
    Path pythonExe,
    String voice,
    String profile
) {

    /**
     * The single socket hooks use to talk to the application. The path is
     * computed in one place: {@code Main} listens on exactly this one, and
     * {@code TaskDispatcher} writes exactly this one into the agent's settings file.
     */
    public Path socketPath() {
        return runDir.resolve("aura.sock");
    }

    public static AuraConfig defaults() {
        Path appData = Path.of(System.getenv().getOrDefault("APPDATA",
            System.getProperty("user.home")), "Aura");
        Path localAppData = Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
            System.getProperty("user.home")), "Aura");
        return new AuraConfig(
            Path.of("claude"),
            Path.of("codex"),
            appData.resolve("projects.yaml"),
            Path.of("aura-hook", "target", "aura-hook.jar").toAbsolutePath(),
            Path.of(System.getProperty("java.home"), "bin", "java.exe"),
            localAppData.resolve("run"),
            Duration.ofMinutes(15),
            Duration.ofSeconds(20),
            Path.of("sidecar").toAbsolutePath(),
            Path.of("sidecar", ".venv", "Scripts", "python.exe").toAbsolutePath(),
            // No voice until one has been chosen by ear (RISK-4). Empty means the
            // narrator writes its line to the log and the tray and says nothing
            // aloud — an application that starts talking before anyone picked how
            // it sounds is a worse first impression than one that stays quiet.
            "",
            // English is the base language; Russian is a profile the owner turns
            // on, which is the project's rule everywhere else and has no reason
            // to be different here.
            "en");
    }

    public static AuraConfig load(Path yamlFile) {
        AuraConfig defaults = defaults();
        if (!Files.isRegularFile(yamlFile)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) {
                return defaults;
            }
            return new AuraConfig(
                path(root, "claudeExe", defaults.claudeExe()),
                path(root, "codexExe", defaults.codexExe()),
                path(root, "projectsFile", defaults.projectsFile()),
                path(root, "hookJar", defaults.hookJar()),
                path(root, "javaExe", defaults.javaExe()),
                path(root, "runDir", defaults.runDir()),
                seconds(root, "idleTimeoutSec", defaults.idleTimeout()),
                seconds(root, "confirmTimeoutSec", defaults.confirmTimeout()),
                path(root, "sidecarDir", defaults.sidecarDir()),
                path(root, "pythonExe", defaults.pythonExe()),
                text(root, "voice", defaults.voice()),
                text(root, "profile", defaults.profile()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to read configuration: " + yamlFile, e);
        }
    }

    private static String text(Map<String, Object> root, String key, String fallback) {
        Object value = root.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    /** The sidecar as a command line: the module is run, never a file path. */
    public java.util.List<String> sidecarCommand() {
        java.util.List<String> command = new java.util.ArrayList<>(
            java.util.List.of(pythonExe.toString(), "-m", "aura_speech.main"));
        if (!voice.isBlank()) {
            command.add("--voice");
            command.add(voice);
        }
        return java.util.List.copyOf(command);
    }

    private static Path path(Map<String, Object> root, String key, Path fallback) {
        Object value = root.get(key);
        return value == null ? fallback : Path.of(String.valueOf(value));
    }

    private static Duration seconds(Map<String, Object> root, String key, Duration fallback) {
        Object value = root.get(key);
        return value == null ? fallback : Duration.ofSeconds(Long.parseLong(String.valueOf(value)));
    }
}
