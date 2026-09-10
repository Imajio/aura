package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AuraConfig#sidecarCommand()} decides whether the microphone is ever asked
 * to open. Every case below asserts the exact argument list, chosen so that a
 * specific regression — {@code --listen} re-tied to a file instead of to the
 * {@code listen} flag, or either file gate dropped — makes it fail.
 */
class AuraConfigTest {

    private static final Path PYTHON_EXE = Path.of("python.exe");

    /** Only the four arguments below vary across the grid this class tests. */
    private static AuraConfig config(Path wakeModel, Path speakerModel, Path speakerReference,
                                     boolean listen) {
        return new AuraConfig(
            Path.of("claude"), Path.of("codex"),
            Path.of("projects.yaml"), Path.of("aura-hook.jar"),
            Path.of("java.exe"), Path.of("run"),
            Duration.ofMinutes(15), Duration.ofSeconds(20),
            Path.of("sidecar"), PYTHON_EXE,
            "", "en",
            wakeModel, speakerModel, speakerReference, listen);
    }

    @Test
    void listenFalseAndNoWakeModelAddsNeitherFlag(@TempDir Path tmp) {
        AuraConfig config = config(tmp.resolve("wake-word.npz"), tmp.resolve("speaker.onnx"),
            tmp.resolve("reference.npy"), false);

        assertThat(config.sidecarCommand())
            .containsExactly(PYTHON_EXE.toString(), "-m", "aura_speech.main");
    }

    @Test
    void listenFalseWithWakeModelPresentAddsTheModelButNotListen(@TempDir Path tmp) throws Exception {
        // This is the case that would regress if --listen were re-tied to the
        // wake-word file's existence instead of to the listen flag.
        Path wakeModel = Files.createFile(tmp.resolve("wake-word.npz"));
        AuraConfig config = config(wakeModel, tmp.resolve("speaker.onnx"),
            tmp.resolve("reference.npy"), false);

        assertThat(config.sidecarCommand()).containsExactly(
            PYTHON_EXE.toString(), "-m", "aura_speech.main",
            "--wake-model", wakeModel.toString());
    }

    @Test
    void listenTrueWithNoWakeModelAddsListenButNotTheModel(@TempDir Path tmp) {
        // The sidecar is asked to listen and refuses with NO_WAKE_WORD, audibly —
        // that refusal is the point, not a case to route around here.
        AuraConfig config = config(tmp.resolve("wake-word.npz"), tmp.resolve("speaker.onnx"),
            tmp.resolve("reference.npy"), true);

        assertThat(config.sidecarCommand())
            .containsExactly(PYTHON_EXE.toString(), "-m", "aura_speech.main", "--listen");
    }

    @Test
    void listenTrueWithWakeModelPresentAddsBoth(@TempDir Path tmp) throws Exception {
        Path wakeModel = Files.createFile(tmp.resolve("wake-word.npz"));
        AuraConfig config = config(wakeModel, tmp.resolve("speaker.onnx"),
            tmp.resolve("reference.npy"), true);

        assertThat(config.sidecarCommand()).containsExactly(
            PYTHON_EXE.toString(), "-m", "aura_speech.main",
            "--wake-model", wakeModel.toString(), "--listen");
    }

    @Test
    void speakerModelIsAddedOnlyWhenItsFileExists(@TempDir Path tmp) throws Exception {
        Path speakerModel = Files.createFile(tmp.resolve("speaker.onnx"));
        AuraConfig config = config(tmp.resolve("wake-word.npz"), speakerModel,
            tmp.resolve("reference.npy"), false);

        assertThat(config.sidecarCommand()).containsExactly(
            PYTHON_EXE.toString(), "-m", "aura_speech.main",
            "--speaker-model", speakerModel.toString());
    }

    @Test
    void speakerReferenceIsAddedOnlyWhenItsFileExists(@TempDir Path tmp) throws Exception {
        Path speakerReference = Files.createFile(tmp.resolve("reference.npy"));
        AuraConfig config = config(tmp.resolve("wake-word.npz"), tmp.resolve("speaker.onnx"),
            speakerReference, false);

        assertThat(config.sidecarCommand()).containsExactly(
            PYTHON_EXE.toString(), "-m", "aura_speech.main",
            "--speaker-reference", speakerReference.toString());
    }

    // The six cases above build an AuraConfig through the record constructor, which
    // covers sidecarCommand() but not AuraConfig.load() itself — the code path an
    // owner actually goes through by editing config.yaml. The two tests below cover
    // that path: a literal `listen` key is what finding 1 requires reading, and
    // absolutising the three new paths (and only those) is what finding 2 requires.

    @Test
    void loadReadsTheListenFlagAndAbsolutisesTheThreeNewPaths(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), String.join("\n",
            "listen: true",
            "wakeModel: voice/wake-word.npz",
            "speakerModel: models/speaker.onnx",
            "speakerReference: voice/reference.npy",
            "claudeExe: claude",
            ""));

        AuraConfig config = AuraConfig.load(yaml);

        assertThat(config.listen()).isTrue();
        assertThat(config.wakeModel())
            .isEqualTo(Path.of("voice", "wake-word.npz").toAbsolutePath());
        assertThat(config.speakerModel())
            .isEqualTo(Path.of("models", "speaker.onnx").toAbsolutePath());
        assertThat(config.speakerReference())
            .isEqualTo(Path.of("voice", "reference.npy").toAbsolutePath());
        // The asymmetry finding 2 protects: claudeExe reaches ProcessBuilder bare,
        // relying on PATH lookup, and must stay exactly as configured rather than
        // being resolved against the current directory.
        assertThat(config.claudeExe()).isEqualTo(Path.of("claude"));
    }

    @Test
    void loadDefaultsListenToFalseWhenTheKeyIsAbsent(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "claudeExe: claude\n");

        AuraConfig config = AuraConfig.load(yaml);

        assertThat(config.listen()).isFalse();
    }

    @Test
    void loadReadsBothBooleanSpellings(@TempDir Path tmp) throws Exception {
        Path on = Files.writeString(tmp.resolve("on.yaml"), "listen: true\n");
        Path off = Files.writeString(tmp.resolve("off.yaml"), "listen: false\n");

        assertThat(AuraConfig.load(on).listen()).isTrue();
        assertThat(AuraConfig.load(off).listen()).isFalse();
    }

    // The two below are the rejected forms. Boolean.parseBoolean answers false to
    // everything it does not recognise, so before this both of them left the
    // microphone shut and said nothing — the silent failure the `listen` key was
    // introduced to remove, arriving on a different input. The message is asserted
    // on the thrown IllegalStateException rather than on its cause because that is
    // the one the startup dialog puts in front of the owner.

    @Test
    void loadRejectsAMisspeltListenValue(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "listen: ture\n");

        assertThatThrownBy(() -> AuraConfig.load(yaml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("listen")
            .hasMessageContaining("got 'ture'");
    }

    @Test
    void loadRejectsANumericListenValue(@TempDir Path tmp) throws Exception {
        // `listen: 1` reads as "on" to a person and as false to parseBoolean.
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "listen: 1\n");

        assertThatThrownBy(() -> AuraConfig.load(yaml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("listen")
            .hasMessageContaining("got '1'");
    }

    // The tests below cover save(), the other half of the round trip load() has had
    // since M1. VoiceChoicePanel is the one caller: it loads the file fresh, changes
    // voice and profile, and saves — so what matters most is not what save() writes,
    // it is what save() leaves alone.

    /**
     * The exact case the brief names: a hand-written comment and two keys save() does
     * not know about (claudeExe, idleTimeoutSec) must survive a save that changes
     * voice. Breaks if save() ever moves from a targeted line rewrite to parsing the
     * file into a map and re-dumping it through SnakeYAML — Yaml().dump() drops every
     * comment even when it keeps every key, which this test would still catch on the
     * comment line alone.
     */
    @Test
    void saveKeepsEveryKeyAndCommentItDoesNotOwn(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, String.join("\n",
            "# a setting the owner wrote by hand; not one save() ever touches",
            "claudeExe: C:\\tools\\claude.exe",
            "idleTimeoutSec: 900",
            "voice: aidar",
            "profile: en",
            "listen: false",
            ""));

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        List<String> lines = Files.readAllLines(yaml);
        assertThat(lines).contains(
            "# a setting the owner wrote by hand; not one save() ever touches",
            "claudeExe: C:\\tools\\claude.exe",
            "idleTimeoutSec: 900");
    }

    /**
     * The other half of the same save: the three keys save() does own must actually
     * change. Breaks if save() were a no-op, or if it wrote the pre-change values.
     */
    @Test
    void saveWritesTheNewVoiceProfileAndListen(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "voice: aidar\nprofile: en\nlisten: false\n");

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        List<String> lines = Files.readAllLines(yaml);
        assertThat(lines).contains("voice: xenia", "profile: ru", "listen: false");
    }

    /**
     * Breaks if upsert() always appends instead of replacing a key already present —
     * the file would grow a new "voice:" line on every save instead of updating the
     * one that is there, and a human reading the file (or a naive script re-reading
     * only the first match) would see the voice chosen on session one forever.
     */
    @Test
    void saveReplacesAnExistingLineRatherThanDuplicatingIt(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "voice: aidar\nprofile: en\nlisten: false\n");

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);
        AuraConfig.load(yaml).withVoice("baya", "ru").save(yaml);

        List<String> lines = Files.readAllLines(yaml);
        assertThat(lines).filteredOn(line -> line.startsWith("voice:")).containsExactly("voice: baya");
        assertThat(lines).hasSize(3);
    }

    /**
     * Breaks if upsert() matched on the key as a substring or prefix instead of the
     * whole key name — "voice" would then also match "voiceSomethingElse", overwriting
     * a key that happens to start the same way instead of leaving it alone and adding
     * "voice" as its own line.
     */
    @Test
    void saveDoesNotConfuseAKeyWithOneThatSharesItsPrefix(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "voiceSomethingElse: untouched\n");

        AuraConfig.defaults().withVoice("xenia", "ru").save(yaml);

        List<String> lines = Files.readAllLines(yaml);
        assertThat(lines).contains("voiceSomethingElse: untouched", "voice: xenia");
    }

    /**
     * Breaks if save() reads with Files.readAllLines and writes with
     * Files.write(Path, List) — both discard the file's actual line terminator, and
     * the latter appends System.lineSeparator() after every line including the last.
     * On Windows that turns a plain \n file with no trailing newline — the owner's
     * real config.yaml is exactly this shape — into \r\n throughout, rewriting the
     * bytes of every line, not just the three save() owns. The file is written with
     * Files.write(Path, byte[]) here rather than a text API, so the bytes on disk
     * before save() runs are exactly what this test asks for.
     */
    @Test
    void savePreservesTheFilesLineEndingAndTrailingNewlineExactly(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, ("hookJar: C:\\tools\\claude.exe\nvoice: aidar\nprofile: en\nlisten: false")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        String saved = Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(saved).isEqualTo(
            "hookJar: C:\\tools\\claude.exe\nvoice: xenia\nprofile: ru\nlisten: false");
    }

    /** The other direction of the same guarantee: CRLF and a trailing newline survive too. */
    @Test
    void savePreservesCrlfAndATrailingNewlineWhenTheFileHasThem(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, "voice: aidar\r\nprofile: en\r\nlisten: false\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        String saved = Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(saved).isEqualTo("voice: xenia\r\nprofile: ru\r\nlisten: false\r\n");
    }

    /** Breaks if save() assumed the file already exists instead of creating it. */
    @Test
    void saveCreatesTheFileWhenItDoesNotExistYet(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("new-config.yaml");

        AuraConfig.defaults().withVoice("baya", "ru").save(yaml);

        assertThat(Files.readAllLines(yaml)).contains("voice: baya", "profile: ru", "listen: false");
    }

    /**
     * Breaks if withVoice() were implemented by re-deriving defaults() instead of
     * copying every other field of the receiver — the owner's claudeExe, timeouts and
     * every path would silently reset to the factory defaults the moment a voice was
     * chosen from the window.
     */
    @Test
    void withVoiceChangesOnlyVoiceAndProfile() {
        AuraConfig original = AuraConfig.defaults();

        AuraConfig changed = original.withVoice("xenia", "ru");

        assertThat(changed.voice()).isEqualTo("xenia");
        assertThat(changed.profile()).isEqualTo("ru");
        assertThat(changed.claudeExe()).isEqualTo(original.claudeExe());
        assertThat(changed.hookJar()).isEqualTo(original.hookJar());
        assertThat(changed.idleTimeout()).isEqualTo(original.idleTimeout());
        assertThat(changed.listen()).isEqualTo(original.listen());
    }
}
