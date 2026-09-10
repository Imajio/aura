package aura.app.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the narrator-voice audition tree from disk: {@code <release>/<voice>/<line>.wav}.
 *
 * <p>Produced once, by hand, outside the repository — {@code
 * sidecar/audition-russian-tts.py} renders it and this class only ever reads what is
 * already there. Nothing here knows what any of the ten lines mean; that is a decision
 * for whoever presents them, not for a file scanner.
 *
 * <p>The folder is optional. It exists on the machine that ran the render script and on
 * no other, so {@link #scan} answers an absent, renamed or unreadable root with an
 * empty list rather than a stack trace — a window that fails to open because an
 * evaluation artefact is missing is a worse failure than one that opens and says there
 * is nothing to audition.
 */
public final class AuditionLibrary {

    private static final Logger log = LoggerFactory.getLogger(AuditionLibrary.class);

    private static final String WAV_SUFFIX = ".wav";

    private AuditionLibrary() {
    }

    /**
     * One voice of one release: its lines, keyed by the line id the file was named
     * with (the {@code .wav} stripped), so a caller can ask for {@code "permission-ask"}
     * without knowing the path it lives at.
     */
    public record AuditionVoice(String release, String name, Map<String, Path> lines) {
    }

    /**
     * Every voice under {@code root} that has at least one {@code .wav} file, in a
     * fixed (alphabetical by release, then by voice) order — the order a caller
     * shuffles for blind listening, not an order meant to be shown as-is.
     *
     * <p>A voice directory with no {@code .wav} files in it is skipped rather than
     * returned with an empty {@code lines} map: an entry nobody can press play on is
     * not a voice, it is a name with nothing behind it.
     */
    public static List<AuditionVoice> scan(Path root) {
        List<AuditionVoice> voices = new ArrayList<>();
        for (Path releaseDir : subdirectoriesOf(root)) {
            for (Path voiceDir : subdirectoriesOf(releaseDir)) {
                Map<String, Path> lines = wavFilesOf(voiceDir);
                if (!lines.isEmpty()) {
                    voices.add(new AuditionVoice(
                        releaseDir.getFileName().toString(),
                        voiceDir.getFileName().toString(),
                        lines));
                }
            }
        }
        return List.copyOf(voices);
    }

    /**
     * {@code voices}, reordered — the same way for the same seed, a different way for
     * a different one. The panel draws one seed when it is built and keeps it for as
     * long as it is open, which is what makes the blind order "shuffled per session but
     * stable within it" rather than reshuffling on every repaint.
     *
     * <p>{@code voices} itself is left untouched, so a caller may shuffle the one scan
     * result more than once — for two separate panels open at once, say — without one
     * shuffle's order leaking into the other's starting point.
     */
    public static List<AuditionVoice> shuffled(List<AuditionVoice> voices, long seed) {
        List<AuditionVoice> copy = new ArrayList<>(voices);
        Collections.shuffle(copy, new Random(seed));
        return List.copyOf(copy);
    }

    private static List<Path> subdirectoriesOf(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, Files::isDirectory)) {
            entries.forEach(found::add);
        } catch (IOException e) {
            // A directory that Files.isDirectory just confirmed can still fail to list
            // — removed, or permissions changed, between the two calls. Either way the
            // audition is optional: log it and move on as if this branch were empty,
            // rather than let a race condition become a startup failure.
            log.debug("could not list {}, treating it as empty", dir, e);
            return List.of();
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return found;
    }

    private static Map<String, Path> wavFilesOf(Path voiceDir) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(voiceDir, "*" + WAV_SUFFIX)) {
            entries.forEach(found::add);
        } catch (IOException e) {
            log.debug("could not list {}, treating it as empty", voiceDir, e);
            return Map.of();
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        Map<String, Path> lines = new LinkedHashMap<>();
        for (Path wav : found) {
            String fileName = wav.getFileName().toString();
            String line = fileName.substring(0, fileName.length() - WAV_SUFFIX.length());
            lines.put(line, wav);
        }
        return Map.copyOf(lines);
    }
}
