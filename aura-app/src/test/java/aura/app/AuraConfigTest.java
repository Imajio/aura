package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aura.app.ui.Theme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AuraConfig#sidecarCommand()} decides whether the microphone is ever asked
 * to open. Every case below asserts the exact argument list, chosen so that a
 * specific regression - {@code --listen} re-tied to a file instead of to the
 * {@code listen} flag, or either file gate dropped - makes it fail.
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
            wakeModel, speakerModel, speakerReference, listen, Theme.Mode.DARK, 0, 0);
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
        // The sidecar is asked to listen and refuses with NO_WAKE_WORD, audibly -
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
    // covers sidecarCommand() but not AuraConfig.load() itself - the code path an
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
    // microphone shut and said nothing - the silent failure the `listen` key was
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

    // theme joins voice, profile and listen: read the same way listen is, defaulting
    // to dark - jet-swing-design-system's own default - rather than to whichever mode
    // Theme happens to hold in memory when nothing in config.yaml says otherwise.

    @Test
    void loadReadsBothThemeSpellings(@TempDir Path tmp) throws Exception {
        Path dark = Files.writeString(tmp.resolve("dark.yaml"), "theme: dark\n");
        Path light = Files.writeString(tmp.resolve("light.yaml"), "theme: light\n");

        assertThat(AuraConfig.load(dark).theme()).isEqualTo(Theme.Mode.DARK);
        assertThat(AuraConfig.load(light).theme()).isEqualTo(Theme.Mode.LIGHT);
    }

    @Test
    void loadDefaultsThemeToDarkWhenTheKeyIsAbsent(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "claudeExe: claude\n");

        assertThat(AuraConfig.load(yaml).theme()).isEqualTo(Theme.Mode.DARK);
    }

    /**
     * Breaks if theme() ever falls back to the default instead of throwing on a
     * spelling it does not recognise - the same silent failure this project has
     * already fixed twice on {@code listen}, moved onto a third input.
     */
    @Test
    void loadRejectsAnUnrecognisedThemeValue(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "theme: purple\n");

        assertThatThrownBy(() -> AuraConfig.load(yaml))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("theme")
            .hasMessageContaining("got 'purple'");
    }

    // The tests below cover save(), the other half of the round trip load() has had
    // since M1. VoiceChoicePanel is the one caller: it loads the file fresh, changes
    // voice and profile, and saves - so what matters most is not what save() writes,
    // it is what save() leaves alone.

    /**
     * The exact case the brief names: a hand-written comment and two keys save() does
     * not know about (claudeExe, idleTimeoutSec) must survive a save that changes
     * voice. Breaks if save() ever moves from a targeted line rewrite to parsing the
     * file into a map and re-dumping it through SnakeYAML - Yaml().dump() drops every
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
     * Breaks if upsert() always appends instead of replacing a key already present -
     * the file would grow a new "voice:" line on every save instead of updating the
     * one that is there, and a human reading the file (or a naive script re-reading
     * only the first match) would see the voice chosen on session one forever.
     *
     * <p>The fixture has no {@code theme:} line, so save() appends exactly one for
     * it (there is no key to replace) alongside the three it is already exercising -
     * that is why four lines, not three, is the right count once theme joins voice,
     * profile and listen.
     */
    @Test
    void saveReplacesAnExistingLineRatherThanDuplicatingIt(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "voice: aidar\nprofile: en\nlisten: false\n");

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);
        AuraConfig.load(yaml).withVoice("baya", "ru").save(yaml);

        List<String> lines = Files.readAllLines(yaml);
        assertThat(lines).filteredOn(line -> line.startsWith("voice:")).containsExactly("voice: baya");
        assertThat(lines).hasSize(4);
    }

    /**
     * Breaks if upsert() matched on the key as a substring or prefix instead of the
     * whole key name - "voice" would then also match "voiceSomethingElse", overwriting
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
     * Files.write(Path, List) - both discard the file's actual line terminator, and
     * the latter appends System.lineSeparator() after every line including the last.
     * On Windows that turns a plain \n file with no trailing newline - the owner's
     * real config.yaml is exactly this shape - into \r\n throughout, rewriting the
     * bytes of every line, not just the three save() owns. The file is written with
     * Files.write(Path, byte[]) here rather than a text API, so the bytes on disk
     * before save() runs are exactly what this test asks for.
     *
     * <p>The fixture has no {@code theme:} line either, so - like the missing voice/
     * profile/listen lines the other save() tests append - save() appends one, here
     * defaulted to "dark", after the line it does own last. That append is the new
     * behaviour theme joining the other three settings is expected to add; the
     * property this test is actually about, line ending and trailing newline, holds
     * for it exactly as it does for the other three.
     */
    @Test
    void savePreservesTheFilesLineEndingAndTrailingNewlineExactly(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, ("hookJar: C:\\tools\\claude.exe\nvoice: aidar\nprofile: en\nlisten: false")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        String saved = Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(saved).isEqualTo(
            "hookJar: C:\\tools\\claude.exe\nvoice: xenia\nprofile: ru\nlisten: false\ntheme: dark");
    }

    /** The other direction of the same guarantee: CRLF and a trailing newline survive too. */
    @Test
    void savePreservesCrlfAndATrailingNewlineWhenTheFileHasThem(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, "voice: aidar\r\nprofile: en\r\nlisten: false\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.load(yaml).withVoice("xenia", "ru").save(yaml);

        String saved = Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(saved).isEqualTo("voice: xenia\r\nprofile: ru\r\nlisten: false\r\ntheme: dark\r\n");
    }

    /**
     * The exact case Task 2's brief names: hookJar, voice, profile and listen - plus
     * the file's own line ending and lack of a trailing newline - must all survive a
     * save that changes only theme. hookJar and listen are given non-default values
     * in the fixture for the same reason {@link #withVoiceChangesOnlyVoiceAndProfile}
     * loads its fixture rather than building it from {@link AuraConfig#defaults()}: a
     * withTheme() that re-derived every other field from defaults() instead of
     * copying the receiver would revert both here, rather than agreeing with the
     * correct copy by coincidence.
     */
    @Test
    void saveAfterAThemeChangePreservesHookJarVoiceProfileAndListenExactly(@TempDir Path tmp)
            throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, ("hookJar: C:\\custom\\aura-hook.jar\nvoice: aidar\nprofile: en\n"
            + "listen: true\ntheme: light").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.load(yaml).withTheme(Theme.Mode.DARK).save(yaml);

        String saved = Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(saved).isEqualTo("hookJar: C:\\custom\\aura-hook.jar\nvoice: aidar\nprofile: en\n"
            + "listen: true\ntheme: dark");
    }

    /** Breaks if save() assumed the file already exists instead of creating it. */
    @Test
    void saveCreatesTheFileWhenItDoesNotExistYet(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("new-config.yaml");

        AuraConfig.defaults().withVoice("baya", "ru").save(yaml);

        assertThat(Files.readAllLines(yaml)).contains("voice: baya", "profile: ru", "listen: false");
    }

    /**
     * Breaks if withVoice() re-derives every field from defaults() instead of copying
     * the receiver. The fixture is loaded from a file with non-default values in it,
     * not built with defaults() directly, so a re-derive and a copy actually disagree
     * instead of producing the same result by coincidence.
     */
    @Test
    void withVoiceChangesOnlyVoiceAndProfile(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, String.join("\n",
            "claudeExe: C:\\custom\\claude.exe",
            "hookJar: C:\\custom\\aura-hook.jar",
            "idleTimeoutSec: 999",
            "listen: true",
            "voice: aidar",
            "profile: en",
            "windowWidth: 1000",
            "windowHeight: 700",
            ""));
        AuraConfig original = AuraConfig.load(yaml);

        AuraConfig changed = original.withVoice("xenia", "ru");

        assertThat(changed.voice()).isEqualTo("xenia");
        assertThat(changed.profile()).isEqualTo("ru");
        assertThat(changed.claudeExe()).isEqualTo(original.claudeExe());
        assertThat(changed.hookJar()).isEqualTo(original.hookJar());
        assertThat(changed.idleTimeout()).isEqualTo(original.idleTimeout());
        assertThat(changed.listen()).isEqualTo(original.listen());
        assertThat(changed.theme()).isEqualTo(original.theme());
        assertThat(changed.windowWidth()).isEqualTo(1000);
        assertThat(changed.windowHeight()).isEqualTo(700);
    }

    /**
     * The same property as {@link #withVoiceChangesOnlyVoiceAndProfile}, for
     * withTheme(): breaks if it re-derives every other field from defaults()
     * instead of copying the receiver. hookJar and listen are non-default in the
     * fixture so a re-derive would revert them rather than agree with the copy
     * by coincidence.
     */
    @Test
    void withThemeChangesOnlyTheme(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, String.join("\n",
            "claudeExe: C:\\custom\\claude.exe",
            "hookJar: C:\\custom\\aura-hook.jar",
            "idleTimeoutSec: 999",
            "listen: true",
            "voice: aidar",
            "profile: en",
            "theme: light",
            "windowWidth: 1000",
            "windowHeight: 700",
            ""));
        AuraConfig original = AuraConfig.load(yaml);

        AuraConfig changed = original.withTheme(Theme.Mode.DARK);

        assertThat(changed.theme()).isEqualTo(Theme.Mode.DARK);
        assertThat(changed.voice()).isEqualTo(original.voice());
        assertThat(changed.profile()).isEqualTo(original.profile());
        assertThat(changed.claudeExe()).isEqualTo(original.claudeExe());
        assertThat(changed.hookJar()).isEqualTo(original.hookJar());
        assertThat(changed.idleTimeout()).isEqualTo(original.idleTimeout());
        assertThat(changed.listen()).isEqualTo(original.listen());
        assertThat(changed.windowWidth()).isEqualTo(1000);
        assertThat(changed.windowHeight()).isEqualTo(700);
    }

    // windowWidth and windowHeight: the size the window closed at, written by
    // saveWindowSize() alone. save() writes the four settings the window's controls
    // change and must leave these two lines as it found them, and saveWindowSize()
    // must leave everything else - including the line ending and the trailing
    // newline the save() tests above pin - exactly as it found it.

    @Test
    void loadReadsTheWindowSize(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"),
            "windowWidth: 1000\nwindowHeight: 700\n");

        AuraConfig config = AuraConfig.load(yaml);

        assertThat(config.windowWidth()).isEqualTo(1000);
        assertThat(config.windowHeight()).isEqualTo(700);
    }

    /** Zero is "never saved": the window then opens at its own default size. */
    @Test
    void loadLeavesTheWindowSizeUnsavedWhenTheKeysAreAbsent(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "claudeExe: claude\n");

        AuraConfig config = AuraConfig.load(yaml);

        assertThat(config.windowWidth()).isZero();
        assertThat(config.windowHeight()).isZero();
        assertThat(AuraConfig.defaults().windowWidth()).isZero();
        assertThat(AuraConfig.defaults().windowHeight()).isZero();
    }

    /**
     * Breaks if a size that is not a whole number above zero is quietly replaced by
     * the default instead of being named, the way listen and theme already refuse
     * to guess.
     */
    @Test
    void loadRejectsAWindowSizeThatIsNotAWholeNumberAboveZero(@TempDir Path tmp) throws Exception {
        for (String line : List.of("windowWidth: wide", "windowHeight: 0", "windowWidth: -900",
                "windowHeight: 640.5")) {
            Path yaml = Files.writeString(tmp.resolve("config.yaml"), line + "\n");
            String key = line.substring(0, line.indexOf(':'));
            String literal = line.substring(line.indexOf(':') + 1).trim();

            assertThatThrownBy(() -> AuraConfig.load(yaml))
                .as(line)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key)
                .hasMessageContaining("got '" + literal + "'");
        }
    }

    /**
     * Closing the window must not add settings nobody chose. Breaks if
     * saveWindowSize() ever goes through save(), which appends voice, profile,
     * listen and theme at their defaults to a file that never set them.
     */
    @Test
    void saveWindowSizeWritesItsTwoKeysAndNothingElse(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, String.join("\n",
            "# a setting written by hand",
            "claudeExe: C:\\tools\\claude.exe",
            "idleTimeoutSec: 900",
            ""));

        AuraConfig.saveWindowSize(yaml, 1000, 700);

        assertThat(Files.readString(yaml)).isEqualTo(String.join("\n",
            "# a setting written by hand",
            "claudeExe: C:\\tools\\claude.exe",
            "idleTimeoutSec: 900",
            "windowWidth: 1000",
            "windowHeight: 700",
            ""));
    }

    /** The same guarantee savePreservesTheFilesLineEndingAndTrailingNewlineExactly pins for save(). */
    @Test
    void saveWindowSizePreservesTheFilesLineEndingAndTrailingNewlineExactly(@TempDir Path tmp)
            throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, ("hookJar: C:\\tools\\claude.exe\nvoice: aidar\nprofile: en\nlisten: false")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.saveWindowSize(yaml, 1000, 700);

        assertThat(Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "hookJar: C:\\tools\\claude.exe\nvoice: aidar\nprofile: en\nlisten: false"
                + "\nwindowWidth: 1000\nwindowHeight: 700");
    }

    @Test
    void saveWindowSizePreservesCrlfAndATrailingNewlineWhenTheFileHasThem(@TempDir Path tmp)
            throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, "voice: aidar\r\nprofile: en\r\nlisten: false\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.saveWindowSize(yaml, 1000, 700);

        assertThat(Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
            "voice: aidar\r\nprofile: en\r\nlisten: false\r\nwindowWidth: 1000\r\nwindowHeight: 700\r\n");
    }

    /**
     * The window saves on every close, so a file that appended instead of replacing
     * would grow two lines a day. Each line is replaced where it stands, with the
     * line between them left alone.
     */
    @Test
    void saveWindowSizeReplacesTheLinesAlreadyThereRatherThanDuplicatingThem(@TempDir Path tmp)
            throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "windowWidth: 900\ntheme: light\nwindowHeight: 640\n");

        AuraConfig.saveWindowSize(yaml, 1000, 700);
        AuraConfig.saveWindowSize(yaml, 1100, 750);

        assertThat(Files.readString(yaml))
            .isEqualTo("windowWidth: 1100\ntheme: light\nwindowHeight: 750\n");
    }

    @Test
    void saveWindowSizeCreatesTheFileWhenItDoesNotExistYet(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("new-config.yaml");

        AuraConfig.saveWindowSize(yaml, 1000, 700);

        assertThat(Files.readString(yaml)).isEqualTo("windowWidth: 1000\nwindowHeight: 700\n");
        assertThat(AuraConfig.load(yaml).windowWidth()).isEqualTo(1000);
        assertThat(AuraConfig.load(yaml).windowHeight()).isEqualTo(700);
    }

    /**
     * Breaks if save() starts writing the window size. A theme press or a voice
     * choice must leave the two lines exactly as the last close wrote them, and must
     * not add them to a file that has none, even from a config that holds a size.
     */
    @Test
    void saveLeavesTheWindowSizeLinesExactlyAsItFoundThem(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, "voice: aidar\nwindowWidth: 1000\nprofile: en\nlisten: false\n"
            + "theme: dark\nwindowHeight: 700\n");
        Path other = tmp.resolve("other.yaml");
        Files.writeString(other, "voice: aidar\n");
        AuraConfig loaded = AuraConfig.load(yaml);

        loaded.withTheme(Theme.Mode.LIGHT).save(yaml);
        loaded.save(other);

        assertThat(Files.readString(yaml)).isEqualTo("voice: aidar\nwindowWidth: 1000\nprofile: en\n"
            + "listen: false\ntheme: light\nwindowHeight: 700\n");
        assertThat(Files.readString(other)).doesNotContain("window");
    }

    /**
     * A theme switch must not add settings nobody chose. Breaks if saveTheme()
     * goes through save(), which appends voice, profile and listen to a file that
     * never set them: one switch of the theme used to leave "voice: ",
     * "profile: en" and "listen: false" in a file that held none of the three.
     */
    @Test
    void saveThemeWritesItsOneKeyAndNothingElse(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.writeString(yaml, String.join("\n",
            "# a setting written by hand",
            "idleTimeoutSec: 900",
            "theme: dark",
            "windowWidth: 1000",
            ""));

        AuraConfig.saveTheme(yaml, Theme.Mode.LIGHT);

        assertThat(Files.readString(yaml)).isEqualTo(String.join("\n",
            "# a setting written by hand",
            "idleTimeoutSec: 900",
            "theme: light",
            "windowWidth: 1000",
            ""));
        assertThat(AuraConfig.load(yaml).theme()).isEqualTo(Theme.Mode.LIGHT);
    }

    /** The same guarantee the save() and saveWindowSize() tests pin for the file's own bytes. */
    @Test
    void saveThemeAppendsItsKeyAndKeepsCrlfAndTheMissingTrailingNewline(@TempDir Path tmp)
            throws Exception {
        Path yaml = tmp.resolve("config.yaml");
        Files.write(yaml, "voice: aidar\r\nlisten: true"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AuraConfig.saveTheme(yaml, Theme.Mode.DARK);

        assertThat(Files.readString(yaml, java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("voice: aidar\r\nlisten: true\r\ntheme: dark");
    }

    /**
     * Breaks if a failed write is swallowed here: the theme control says under
     * itself that the choice was not saved, and it can only do that if this throws.
     */
    @Test
    void saveThemeThrowsWhenTheFileCannotBeWritten(@TempDir Path tmp) throws Exception {
        Path yaml = Files.writeString(tmp.resolve("config.yaml"), "theme: dark\n");
        assertThat(yaml.toFile().setReadOnly()).isTrue();
        try {
            assertThatThrownBy(() -> AuraConfig.saveTheme(yaml, Theme.Mode.LIGHT))
                .isInstanceOf(java.nio.file.AccessDeniedException.class);
            assertThat(Files.readString(yaml)).isEqualTo("theme: dark\n");
        } finally {
            yaml.toFile().setWritable(true);
        }
    }
}
