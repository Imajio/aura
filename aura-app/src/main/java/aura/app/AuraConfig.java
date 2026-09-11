package aura.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
    String profile,
    Path wakeModel,
    Path speakerModel,
    Path speakerReference,
    boolean listen
) {

    /**
     * The single socket hooks use to talk to the application. The path is
     * computed in one place: {@code Main} listens on exactly this one, and
     * {@code TaskDispatcher} writes exactly this one into the agent's settings file.
     */
    public Path socketPath() {
        return runDir.resolve("aura.sock");
    }

    /**
     * The same settings with a different voice and profile - the pair {@code
     * VoiceChoicePanel}'s "Use this voice" changes together, since every candidate in
     * the audition is a Russian voice and picking one is picking the Russian profile.
     *
     * <p>A copy, not a mutation: {@code AuraConfig} is a record, and the caller saves
     * the result with {@link #save}. Every field besides the two named is carried over
     * from the receiver - never re-derived from {@link #defaults()}, which would reset
     * the owner's own paths and timeouts to the factory ones the moment a voice was
     * chosen from the window.
     */
    public AuraConfig withVoice(String voice, String profile) {
        return new AuraConfig(claudeExe, codexExe, projectsFile, hookJar, javaExe, runDir,
            idleTimeout, confirmTimeout, sidecarDir, pythonExe, voice, profile, wakeModel,
            speakerModel, speakerReference, listen);
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
            // aloud - an application that starts talking before anyone picked how
            // it sounds is a worse first impression than one that stays quiet.
            "",
            // English is the base language; Russian is a profile the owner turns
            // on, which is the project's rule everywhere else and has no reason
            // to be different here.
            "en",
            // None of the three ship with the repository, and they do not arrive the
            // same way. The first and third are the owner's own, produced from their
            // recordings by train-wake-word.py and enrol-speaker.py; the WeSpeaker
            // embedding model between them is a download, and sidecar/README.md
            // carries the command. Whether the sidecar is asked to listen is a
            // separate matter - see the `listen` default just below.
            Path.of("voice", "wake-word.npz").toAbsolutePath(),
            Path.of("models", "wespeaker-resnet34", "voxceleb_resnet34_LM.onnx").toAbsolutePath(),
            Path.of("voice", "reference.npy").toAbsolutePath(),
            // The microphone stays shut unless somebody asked for it, which is
            // what main.py's own --listen help text already says.
            false);
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
                text(root, "profile", defaults.profile()),
                path(root, "wakeModel", defaults.wakeModel()).toAbsolutePath(),
                path(root, "speakerModel", defaults.speakerModel()).toAbsolutePath(),
                path(root, "speakerReference", defaults.speakerReference()).toAbsolutePath(),
                flag(root, "listen", defaults.listen()));
        } catch (Exception e) {
            // The cause's own message is folded in rather than left to the log. The
            // startup dialog shows this message and nothing else, and naming the file
            // without naming what is wrong in it sends the owner back to a config they
            // have already read through once.
            throw new IllegalStateException(
                "failed to read configuration: " + yamlFile + " - " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code voice}, {@code profile} and {@code listen} into {@code yamlFile} -
     * the three settings the window can change - and leaves every other line exactly
     * as it was, key or comment alike.
     *
     * <p>This is a targeted rewrite of the file's lines, not a parse into a map and a
     * re-dump through SnakeYAML: {@code new Yaml().dump(map)} would silently drop
     * every comment even if it kept every key, and could reformat a value the owner
     * typed by hand. A config file the application improves by quietly discarding a
     * key or a comment the owner put there is a worse bug than one that cannot save at
     * all, so each of the three keys below is updated in place if a line already sets
     * it, or appended if none does - and nothing else in the file is touched.
     *
     * <p>"Nothing else" includes the file's own line terminator and whether it ends in
     * one. {@code Files.readAllLines} discards both, and {@code Files.write(Path,
     * Iterable)} puts {@link System#lineSeparator()} back after every line including
     * the last - on Windows that turns a plain {@code \n} file with no trailing newline
     * (the owner's real file is exactly this) into {@code \r\n} throughout, rewriting
     * every line's bytes rather than the three this method owns. Reading and writing
     * the whole file as one string, and detecting both the terminator and the trailing
     * newline from it, is what keeps a file the application never rewrote look
     * unrewritten in every byte save() does not own.
     */
    public void save(Path yamlFile) throws IOException {
        String original = Files.isRegularFile(yamlFile)
            ? Files.readString(yamlFile, StandardCharsets.UTF_8)
            : "";
        String terminator = lineTerminatorOf(original);
        List<String> lines = linesOf(original);
        lines = upsert(lines, "voice", voice);
        lines = upsert(lines, "profile", profile);
        lines = upsert(lines, "listen", Boolean.toString(listen));
        Files.writeString(yamlFile, join(lines, terminator, endsWithNewline(original)),
            StandardCharsets.UTF_8);
    }

    /**
     * The line terminator {@code text} already uses, so a file the owner wrote with
     * plain {@code \n} is written back with plain {@code \n} - never the JVM's platform
     * default. {@code \r\n} is checked first because the pattern for a lone {@code \n}
     * also matches inside it. Text with no terminator at all (new, or a single line)
     * has nothing to detect, so plain {@code \n} is used, matching every other text
     * file in this project.
     */
    private static String lineTerminatorOf(String text) {
        if (text.contains("\r\n")) {
            return "\r\n";
        }
        if (text.contains("\n")) {
            return "\n";
        }
        return text.contains("\r") ? "\r" : "\n";
    }

    private static boolean endsWithNewline(String text) {
        return text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
    }

    /** {@code text} split into lines, with no trailing empty line for a terminator at the end. */
    private static List<String> linesOf(String text) {
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\r|\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            // The empty element String.split leaves after the final terminator - not a
            // blank line the owner wrote. endsWithNewline() is what remembers whether
            // to put a terminator back after the real last line.
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String join(List<String> lines, String terminator, boolean trailingNewline) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                text.append(terminator);
            }
            text.append(lines.get(i));
        }
        if (trailingNewline && !lines.isEmpty()) {
            text.append(terminator);
        }
        return text.toString();
    }

    /**
     * Replaces the line that already sets {@code key}, if there is one, or appends a
     * new line. Matched on the key followed by optional whitespace and a colon, so
     * that {@code voice} can update {@code voice: aidar} without also matching {@code
     * voiceSomethingElse: x} - a longer key that merely starts the same way - and a
     * commented-out {@code # voice: x}, which has something other than whitespace
     * before the key, is left alone rather than mistaken for the active setting.
     */
    private static List<String> upsert(List<String> lines, String key, String value) {
        Pattern activeLine = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*:.*$");
        List<String> result = new ArrayList<>(lines);
        for (int i = 0; i < result.size(); i++) {
            if (activeLine.matcher(result.get(i)).matches()) {
                result.set(i, key + ": " + value);
                return result;
            }
        }
        result.add(key + ": " + value);
        return result;
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
        if (Files.isRegularFile(speakerModel)) {
            command.add("--speaker-model");
            command.add(speakerModel.toString());
        }
        if (Files.isRegularFile(speakerReference)) {
            command.add("--speaker-reference");
            command.add(speakerReference.toString());
        }
        // Listening is asked for explicitly, through the `listen` flag: it is
        // never inferred from what happens to exist on disk. The wake-word model
        // is passed whenever it exists, listen or not, so that asking to listen
        // without one still reaches the sidecar and earns its NO_WAKE_WORD
        // refusal, out loud, instead of being silently dropped here.
        if (Files.isRegularFile(wakeModel)) {
            command.add("--wake-model");
            command.add(wakeModel.toString());
        }
        if (listen) {
            command.add("--listen");
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

    /**
     * A boolean that refuses to guess.
     *
     * <p>{@code Boolean.parseBoolean} answers {@code false} to everything it does not
     * recognise, so {@code listen: 1} and a typo such as {@code listen: ture} would
     * both leave the microphone shut without a word - the same silent failure the
     * {@code listen} key was added to remove, moved onto a different input. SnakeYAML
     * already hands back a real {@link Boolean} for the YAML spellings, so anything
     * that is neither that nor a quoted {@code "true"}/{@code "false"} is a mistake,
     * and saying so is the only way the owner finds it.
     */
    private static boolean flag(Map<String, Object> root, String key, boolean fallback) {
        Object value = root.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String literal = String.valueOf(value).trim();
        if (literal.equalsIgnoreCase("true")) {
            return true;
        }
        if (literal.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
            key + ": expected true or false, got '" + literal + "'");
    }
}
