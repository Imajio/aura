package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
}
