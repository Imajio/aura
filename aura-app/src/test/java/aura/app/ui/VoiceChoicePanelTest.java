package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.ui.AuditionLibrary.AuditionVoice;
import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** VoiceChoicePanel tested by construction and direct component lookup, no rendering needed. */
class VoiceChoicePanelTest {

    /** Breaks if useButton stays enabled with nothing selected, or stays disabled once a row is picked. */
    @Test
    void useButtonAndItsReasonReflectWhetherAVoiceIsSelected(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "voiceA", "running-tests", 50);
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            assertThat(button(panel.panel, "voice.choice.use").isEnabled()).isFalse();
            assertThat(text(panel.panel, "voice.choice.useReason")).contains("select a voice");

            list(panel.panel).setSelectedIndex(0);

            assertThat(button(panel.panel, "voice.choice.use").isEnabled()).isTrue();
            assertThat(text(panel.panel, "voice.choice.useReason")).isEmpty();
        });
    }

    /** Breaks if a line button ignores which lines the selected voice actually has. */
    @Test
    void lineButtonsAreEnabledOnlyForLinesTheSelectedVoiceActuallyHas(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "voiceA", "running-tests", 50);
        playableWav(tmp, "release", "voiceA", "done", 50);
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            assertThat(button(panel.panel, "voice.choice.line.running-tests").isEnabled()).isFalse();
            assertThat(text(panel.panel, "voice.choice.linesReason")).contains("select a voice");

            list(panel.panel).setSelectedIndex(0);

            assertThat(button(panel.panel, "voice.choice.line.running-tests").isEnabled()).isTrue();
            assertThat(button(panel.panel, "voice.choice.line.done").isEnabled()).isTrue();
            assertThat(button(panel.panel, "voice.choice.line.all-green").isEnabled()).isFalse();
            assertThat(text(panel.panel, "voice.choice.linesReason")).isEmpty();
        });
    }

    /** Breaks if playing does not disable the other lines, or a finished clip leaves them dead. */
    @Test
    void everyLineButtonIsDisabledWhilePlayingAndLiveAgainWhenItFinishes(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "voiceA", "running-tests", 300);
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            list(panel.panel).setSelectedIndex(0);
            button(panel.panel, "voice.choice.line.running-tests").doClick();

            assertThat(button(panel.panel, "voice.choice.line.running-tests").isEnabled()).isFalse();
            assertThat(text(panel.panel, "voice.choice.linesReason")).contains("already playing");
        });

        waitUntil(() -> enabledOnEdt(panel.panel, "voice.choice.line.running-tests"), 5000);
        assertThat(textOnEdt(panel.panel, "voice.choice.linesReason")).isEmpty();
    }

    /** Breaks if reveal reshuffled the list, or a row's blind letter changed on a re-hide. */
    @Test
    void blindLabelStaysTheSameAcrossARevealAndAReHide(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "voiceA", "running-tests", 50);
        playableWav(tmp, "release", "voiceB", "running-tests", 50);
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            JList<AuditionVoice> list = list(panel.panel);
            String blindBefore = cellText(list, 0);
            assertThat(blindBefore).isEqualTo("Voice A");

            button(panel.panel, "voice.choice.reveal").doClick();
            String revealed = cellText(list, 0);
            assertThat(revealed).contains(list.getModel().getElementAt(0).name());
            assertThat(revealed).contains(list.getModel().getElementAt(0).release());

            button(panel.panel, "voice.choice.reveal").doClick();
            assertThat(cellText(list, 0)).isEqualTo(blindBefore);
        });
    }

    /** Breaks if a bad file's exception ever escapes the playback thread instead of being reported. */
    @Test
    void aFileThatWillNotPlayReportsOneSentenceWithoutThrowing(@TempDir Path tmp) throws Exception {
        unplayableWav(tmp, "release", "voiceA", "running-tests");
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            list(panel.panel).setSelectedIndex(0);
            button(panel.panel, "voice.choice.line.running-tests").doClick();
        });

        waitUntil(() -> textOnEdt(panel.panel, "voice.choice.playback").contains("Could not play"), 5000);
        String report = textOnEdt(panel.panel, "voice.choice.playback");
        assertThat(report).contains("Could not play").doesNotContain("Exception");
    }

    /** Breaks if a blind row's accessible name or any tooltip carries the real name before reveal. */
    @Test
    void noRealNameLeaksThroughAccessibleNamesOrTooltipsBeforeReveal(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "secretvoicename", "running-tests", 50);
        Panel panel = panel(tmp, tmp.resolve("config.yaml"), 1L);

        onEdt(() -> {
            JList<AuditionVoice> list = list(panel.panel);
            String accessibleName = list.getAccessibleContext().getAccessibleChild(0)
                .getAccessibleContext().getAccessibleName();
            assertThat(accessibleName).isEqualTo("Voice A").doesNotContain("secretvoicename");
            assertThat(list.getToolTipText()).isNull();
            assertThat(button(panel.panel, "voice.choice.line.running-tests").getToolTipText()).isNull();
        });
    }

    /** The fix for the bug this fix round exists for: run this with the gate removed to see it fail red. */
    @Test
    void useThisVoiceDoesNotNameTheVoiceUnlessNamesAreRevealed(@TempDir Path tmp) throws Exception {
        playableWav(tmp, "release", "secretvoicename", "running-tests", 50);
        Path configFile = tmp.resolve("config.yaml");
        Files.writeString(configFile, "voice: aidar\nprofile: en\nlisten: false\n");
        Panel panel = panel(tmp, configFile, 1L);

        onEdt(() -> {
            list(panel.panel).setSelectedIndex(0);
            button(panel.panel, "voice.choice.use").doClick();
        });
        assertThat(textOnEdt(panel.panel, "voice.choice.useReport")).doesNotContain("secretvoicename");

        onEdt(() -> {
            button(panel.panel, "voice.choice.reveal").doClick();
            list(panel.panel).setSelectedIndex(0);
            button(panel.panel, "voice.choice.use").doClick();
        });
        assertThat(textOnEdt(panel.panel, "voice.choice.useReport")).contains("secretvoicename");
    }

    private static final class Panel {
        final VoiceChoicePanel panel;

        Panel(Path auditionRoot, Path configFile, long seed) {
            panel = new VoiceChoicePanel(auditionRoot, configFile, seed);
        }
    }

    private static Panel panel(Path auditionRoot, Path configFile, long seed) {
        Panel[] built = new Panel[1];
        onEdt(() -> built[0] = new Panel(auditionRoot, configFile, seed));
        return built[0];
    }

    private static void playableWav(Path root, String release, String voice, String line, int millis)
            throws Exception {
        Path file = root.resolve(release).resolve(voice).resolve(line + ".wav");
        Files.createDirectories(file.getParent());
        AudioFormat format = new AudioFormat(8000f, 16, 1, true, false);
        int frames = 8000 * millis / 1000;
        byte[] pcm = new byte[frames * 2];
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    private static void unplayableWav(Path root, String release, String voice, String line) throws Exception {
        Path file = root.resolve(release).resolve(voice).resolve(line + ".wav");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not audio");
    }

    @SuppressWarnings("unchecked")
    private static JList<AuditionVoice> list(Container root) {
        return (JList<AuditionVoice>) find(root, "voice.choice.voices");
    }

    private static AbstractButton button(Container root, String name) {
        return (AbstractButton) find(root, name);
    }

    private static String text(Container root, String name) {
        String raw = ((JLabel) find(root, name)).getText();
        return raw.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replaceAll("\\s+", " ").trim();
    }

    private static String textOnEdt(Container root, String name) {
        String[] result = new String[1];
        onEdt(() -> result[0] = text(root, name));
        return result[0];
    }

    private static boolean enabledOnEdt(Container root, String name) {
        boolean[] result = new boolean[1];
        onEdt(() -> result[0] = button(root, name).isEnabled());
        return result[0];
    }

    private static String cellText(JList<AuditionVoice> list, int index) {
        AuditionVoice value = list.getModel().getElementAt(index);
        Component rendered = list.getCellRenderer()
            .getListCellRendererComponent(list, value, index, false, false);
        return ((JLabel) rendered).getText();
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("condition not met in time");
    }

    private static Component find(Container root, String name) {
        Component found = search(root, name);
        if (found == null) {
            throw new AssertionError("no component named '" + name + "' in the panel");
        }
        return found;
    }

    private static Component search(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = search(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void onEdt(Runnable work) {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        }
    }
}
