package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import aura.app.ui.AuditionLibrary.AuditionVoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AuditionLibrary} turns {@code <release>/<voice>/<line>.wav} on disk into the
 * list the panel shows. Every test below runs against a {@code @TempDir} tree built by
 * hand — never against {@code C:\Aura\tts-audition}, which is a fixture nobody is to
 * depend on the shape of.
 */
class AuditionLibraryTest {

    /**
     * Breaks if {@code scan} flattens releases and voices into one list instead of
     * nesting lines under a voice under a release, or if it drops a voice whenever a
     * sibling release or voice exists.
     */
    @Test
    void scanFindsEveryVoiceAcrossTwoReleasesAndTwoVoicesEach(@TempDir Path root) throws Exception {
        wav(root, "releaseA", "voice1", "lineX");
        wav(root, "releaseA", "voice1", "lineY");
        wav(root, "releaseA", "voice2", "lineX");
        wav(root, "releaseA", "voice2", "lineY");
        wav(root, "releaseB", "voice1", "lineX");
        wav(root, "releaseB", "voice1", "lineY");
        wav(root, "releaseB", "voice2", "lineX");
        wav(root, "releaseB", "voice2", "lineY");

        List<AuditionVoice> voices = AuditionLibrary.scan(root);

        assertThat(voices).hasSize(4);
        assertThat(voices).extracting(AuditionVoice::release, AuditionVoice::name)
            .containsExactlyInAnyOrder(
                tuple("releaseA", "voice1"), tuple("releaseA", "voice2"),
                tuple("releaseB", "voice1"), tuple("releaseB", "voice2"));
        for (AuditionVoice voice : voices) {
            assertThat(voice.lines()).hasSize(2);
            assertThat(voice.lines()).containsOnlyKeys("lineX", "lineY");
            assertThat(Files.isRegularFile(voice.lines().get("lineX"))).isTrue();
        }
    }

    /**
     * Breaks if an empty voice directory turns into an {@code AuditionVoice} with an
     * empty {@code lines} map — which would put a nameless, unplayable row in the
     * panel instead of simply not listing that voice.
     */
    @Test
    void scanSkipsAVoiceDirectoryThatHasNoWavFiles(@TempDir Path root) throws Exception {
        wav(root, "releaseA", "hasAudio", "lineX");
        Path empty = root.resolve("releaseA").resolve("empty");
        Files.createDirectories(empty);
        Files.writeString(empty.resolve("read-me.txt"), "not a wav");

        List<AuditionVoice> voices = AuditionLibrary.scan(root);

        assertThat(voices).extracting(AuditionVoice::name).containsExactly("hasAudio");
    }

    /**
     * Breaks if {@code scan} lets {@code Files.newDirectoryStream} throw
     * {@code NoSuchFileException} straight out of the method — the audition folder is
     * optional, and a window that fails to open because it is absent is worse than one
     * that opens and says there is nothing to audition.
     */
    @Test
    void scanOfAMissingRootYieldsAnEmptyListInsteadOfThrowing(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");

        assertThatCode(() -> AuditionLibrary.scan(missing)).doesNotThrowAnyException();
        assertThat(AuditionLibrary.scan(missing)).isEmpty();
    }

    /**
     * Breaks if {@code shuffled} used an unseeded {@code Random} (a session-stable
     * order requires the same seed to always reorder the same list the same way), or
     * if it always returned the input order regardless of seed (which would make
     * "shuffled per session" a lie and leave the panel's row order equal to disk order,
     * defeating blind listening on the one axis it protects — folder order tends to
     * put the current default first).
     */
    @Test
    void shuffledIsStableForASeedAndDiffersBetweenTwoSeeds() {
        List<AuditionVoice> voices = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            voices.add(new AuditionVoice("release", "voice" + i, Map.of()));
        }

        List<AuditionVoice> firstWithSeed1 = AuditionLibrary.shuffled(voices, 1L);
        List<AuditionVoice> secondWithSeed1 = AuditionLibrary.shuffled(voices, 1L);
        List<AuditionVoice> withSeed2 = AuditionLibrary.shuffled(voices, 2L);

        assertThat(firstWithSeed1).extracting(AuditionVoice::name)
            .containsExactlyElementsOf(secondWithSeed1.stream().map(AuditionVoice::name).toList());
        assertThat(firstWithSeed1).extracting(AuditionVoice::name)
            .isNotEqualTo(withSeed2.stream().map(AuditionVoice::name).toList());
        // The input itself is untouched — a caller that scans once and shuffles twice,
        // for two different sessions, must not have the second shuffle see the first
        // shuffle's order as its starting point.
        assertThat(voices).extracting(AuditionVoice::name)
            .containsExactly("voice0", "voice1", "voice2", "voice3", "voice4", "voice5",
                "voice6", "voice7");
    }

    private static void wav(Path root, String release, String voice, String line) throws Exception {
        Path dir = root.resolve(release).resolve(voice);
        Files.createDirectories(dir);
        Files.write(dir.resolve(line + ".wav"), new byte[] {0});
    }
}
