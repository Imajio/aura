package aura.app.ui;

import aura.app.AuraConfig;
import aura.app.ui.AuditionLibrary.AuditionVoice;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Choosing the narrator's Russian voice by ear — RISK-4, open since M1 and blocked on
 * nothing but a person listening. A hundred lines already exist at {@code
 * C:\Aura\tts-audition} (two Silero releases times five voices times the narrator's own
 * ten lines); this panel is the button in front of them, so the choice takes twenty
 * minutes instead of a folder of files and a media player.
 *
 * <h2>Blind, and staying blind until asked not to be</h2>
 *
 * <p>The ten voices are shown as {@code Voice A}…{@code Voice J}, in an order shuffled
 * once when the panel is built and kept for as long as it stays open — not
 * re-shuffled on every repaint, and not sorted back to disk order, which would put the
 * current default first and invite judging it as the default rather than as a voice.
 * {@link #ENGLISH_TERM_LINES} names the two of the ten lines that put an English
 * technical term inside Russian speech: that pairing is the case a Russian voice is
 * most likely to mangle, and the case the product actually lives in, so those two are
 * marked rather than left for the ear to catch by surprise. Real names appear only
 * after {@code Reveal names} is pressed.
 *
 * <h2>Threads</h2>
 *
 * <p>Every line is played on its own short-lived daemon thread: a {@link Clip} is
 * opened, started, waited on and closed there, never on the event dispatch thread. A
 * file that will not open or will not play reports one sentence in the panel and does
 * not throw — {@code Exception}, not the three checked sound exceptions by name,
 * because {@link AudioSystem#getClip()} and {@link Clip#open} can also fail with an
 * unchecked {@code IllegalArgumentException} when nothing on the machine supports the
 * file's format, and that failure deserves the same one sentence, not a stack trace on
 * a thread nobody is watching. Every update the worker makes to a component is posted
 * back with {@link SwingUtilities#invokeLater}.
 *
 * <p>Saving is the one thing done straight from the button's own EDT callback: reading
 * and rewriting a config file of a few short lines is not slow enough to justify a
 * second thread and the state it would need.
 */
public final class VoiceChoicePanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(VoiceChoicePanel.class);

    /**
     * Where {@code sidecar/audition-russian-tts.py} writes its output by default. A
     * fixed absolute path rather than one relative to the working directory, on
     * purpose: the render script's own {@code --out} default already puts it here,
     * outside the repository, deliberately not something the project's own working
     * directory should influence.
     */
    public static final Path DEFAULT_AUDITION_ROOT = Path.of("C:", "Aura", "tts-audition");

    /**
     * The two of the narrator's ten lines that put an English technical term inside
     * Russian speech, read from {@code sidecar/audition-russian-tts.py}'s own {@code
     * PHRASES}: {@code agent-took-it} says "backend", {@code permission-ask} says
     * "build". Named here rather than in {@link AuditionLibrary} — what a line means is
     * this panel's business, not a file scanner's.
     */
    private static final Set<String> ENGLISH_TERM_LINES = Set.of("agent-took-it", "permission-ask");

    /**
     * The narrator's ten lines, in the order {@code audition-russian-tts.py} speaks
     * them rather than alphabetically — pressing down the list then reads as the
     * scenario the product actually tells, not a sorted word list. A voice missing one
     * of these (it should not happen; {@link AuditionLibrary} only says a voice exists
     * once it has at least one line) simply leaves that one button disabled rather than
     * shrinking the list.
     */
    private static final List<String> LINE_ORDER = List.of(
        "agent-took-it", "running-tests", "one-test-failed", "all-green",
        "permission-ask", "declined-timeout", "stopped", "wrong-project",
        "agent-failed", "done");

    /** How long a worker waits for a clip's own STOP event before giving up on it. */
    private static final int PLAYBACK_TIMEOUT_SECONDS = 30;

    private final Path auditionRoot;
    private final Path configFile;
    private final List<AuditionVoice> voices;

    private final JCheckBox revealToggle = new JCheckBox("Reveal names");
    private final DefaultListModel<AuditionVoice> voiceListModel = new DefaultListModel<>();
    private final JList<AuditionVoice> voiceList = new JList<>(voiceListModel);
    private final JButton useButton = new JButton("Use this voice");
    private final JLabel useReason = UiTheme.elastic(UiTheme.hint(""));
    private final JLabel useReport = UiTheme.wrapped("");
    private final JLabel linesReason = UiTheme.hint("");
    private final JLabel playbackStatus = UiTheme.wrapped("");
    private final Map<String, JButton> lineButtons = new LinkedHashMap<>();

    private volatile boolean playing;

    /** The panel Aura actually shows: one shuffle, drawn fresh for this run. */
    VoiceChoicePanel(Path auditionRoot, Path configFile) {
        this(auditionRoot, configFile, new Random().nextLong());
    }

    /**
     * The same panel with the session shuffle pinned to a known seed — what the render
     * harness and any future panel test use, so a captured state does not depend on
     * which order the JVM happened to draw.
     */
    VoiceChoicePanel(Path auditionRoot, Path configFile, long shuffleSeed) {
        this.auditionRoot = auditionRoot;
        this.configFile = configFile;
        this.voices = AuditionLibrary.shuffled(AuditionLibrary.scan(auditionRoot), shuffleSeed);

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(UiTheme.pad(UiTheme.WIDE));

        column.add(leftAligned(UiTheme.title("Choose a voice")));
        column.add(Box.createVerticalStrut(UiTheme.TIGHT));
        column.add(leftAligned(UiTheme.wrapped(
            "The same ten lines, in every candidate voice. Listen blind and judge how "
                + "natural the short, functional line sounds — not how a name you "
                + "already trust would sound saying it.")));
        column.add(Box.createVerticalStrut(UiTheme.WIDE));

        column.add(voicesCard());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(linesCard());

        JScrollPane scroll = new JScrollPane(new ContentPane(column),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(UiTheme.CANVAS);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        setBackground(UiTheme.CANVAS);
        add(scroll, BorderLayout.CENTER);

        for (AuditionVoice voice : voices) {
            voiceListModel.addElement(voice);
        }
        applyEnablement();
    }

    private JComponent voicesCard() {
        Card card = new Card("Candidate voices");
        if (voices.isEmpty()) {
            card.note(UiTheme.wrapped("No audition samples found at " + auditionRoot
                + ". Run sidecar/audition-russian-tts.py to render them.", UiTheme.WARN));
        } else {
            card.note("Ten voices — two Silero releases, five voices each. Names stay "
                + "hidden until you ask for them.");
        }
        revealToggle.setName("voice.choice.reveal");
        revealToggle.setFont(UiTheme.body());
        revealToggle.setOpaque(false);
        revealToggle.setFocusPainted(false);
        revealToggle.setEnabled(!voices.isEmpty());
        revealToggle.addActionListener(e -> voiceList.repaint());
        card.row(revealToggle);

        voiceList.setName("voice.choice.voices");
        voiceList.setFont(UiTheme.body());
        voiceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        voiceList.setVisibleRowCount(6);
        voiceList.setCellRenderer(new VoiceCellRenderer());
        voiceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyEnablement();
            }
        });
        JScrollPane voiceScroll = new JScrollPane(voiceList,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        card.row(voiceScroll);

        useButton.setName("voice.choice.use");
        useButton.setFont(UiTheme.body());
        useButton.setFocusPainted(false);
        useButton.addActionListener(e -> useSelectedVoice());
        useReason.setName("voice.choice.useReason");
        useReport.setName("voice.choice.useReport");
        card.row(row(useButton, useReason));
        card.note(useReport);
        return card;
    }

    private JComponent linesCard() {
        Card card = new Card("Lines");
        card.note("Press a line to hear the selected voice say it. * marks a line that "
            + "puts an English technical term inside the Russian sentence — the case a "
            + "Russian voice is most likely to mangle.");

        JPanel grid = new JPanel(new GridLayout(0, 2, UiTheme.GAP, UiTheme.GAP));
        grid.setOpaque(false);
        for (String lineId : LINE_ORDER) {
            String label = humanize(lineId) + (ENGLISH_TERM_LINES.contains(lineId) ? " *" : "");
            JButton button = new JButton(label);
            button.setName("voice.choice.line." + lineId);
            button.setFont(UiTheme.body());
            button.setFocusPainted(false);
            button.addActionListener(e -> playSelectedLine(lineId));
            lineButtons.put(lineId, button);
            grid.add(button);
        }
        card.row(grid);

        linesReason.setName("voice.choice.linesReason");
        card.note(linesReason);
        playbackStatus.setName("voice.choice.playback");
        card.note(playbackStatus);
        return card;
    }

    private void useSelectedVoice() {
        AuditionVoice selected = voiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        try {
            AuraConfig.load(configFile).withVoice(selected.name(), "ru").save(configFile);
            // Named only after reveal, or this message would do the one thing blind
            // listening exists to prevent: telling the owner which row was which voice.
            String saved = revealToggle.isSelected()
                ? "Saved \"" + selected.name() + "\" and profile \"ru\"."
                : "Saved your choice.";
            useReport.setText(UiTheme.html(saved + " Takes effect the next time Aura starts."));
            useReport.setForeground(UiTheme.GOOD);
        } catch (Exception e) {
            log.warn("could not save the chosen voice to {}", configFile, e);
            useReport.setText(UiTheme.html("Could not save " + configFile + ": " + e.getMessage()));
            useReport.setForeground(UiTheme.BAD);
        }
    }

    private void playSelectedLine(String lineId) {
        AuditionVoice selected = voiceList.getSelectedValue();
        if (selected == null || playing) {
            return;
        }
        Path wav = selected.lines().get(lineId);
        if (wav == null) {
            return;
        }
        playing = true;
        playbackStatus.setText(UiTheme.html("Playing \u201c" + humanize(lineId) + "\u201d…"));
        playbackStatus.setForeground(UiTheme.MUTED);
        applyEnablement();

        Thread worker = new Thread(() -> {
            try {
                playBlocking(wav);
                SwingUtilities.invokeLater(() -> onPlaybackFinished());
            } catch (Exception e) {
                log.warn("could not play {}", wav, e);
                SwingUtilities.invokeLater(() -> onPlaybackFailed(lineId, e));
            }
        }, "voice-choice-playback");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Opens, plays and closes exactly one clip. Runs entirely off the EDT — the
     * caller's thread is a short-lived daemon dedicated to this one line.
     *
     * <p>Catches nothing: every failure here — a missing file, a format nothing on the
     * machine supports, a busy mixer — is left for the caller to log and report as one
     * sentence, uniformly, rather than sorted into which of {@code
     * UnsupportedAudioFileException}, {@code IOException}, {@code
     * LineUnavailableException} or the unchecked {@code IllegalArgumentException}
     * {@link AudioSystem#getClip()} and {@link Clip#open} can each throw.
     */
    private static void playBlocking(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            Clip clip = AudioSystem.getClip();
            try {
                CountDownLatch finished = new CountDownLatch(1);
                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        finished.countDown();
                    }
                });
                clip.open(in);
                clip.start();
                // A line that never fires its own STOP event must still let the panel
                // go — a bounded wait rather than an indefinite one is what keeps a
                // single stuck clip from disabling every line button for the rest of
                // the session.
                finished.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                clip.close();
            }
        }
    }

    private void onPlaybackFinished() {
        playing = false;
        playbackStatus.setText("");
        applyEnablement();
    }

    private void onPlaybackFailed(String lineId, Exception e) {
        playing = false;
        String reason = e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName() : e.getMessage();
        playbackStatus.setText(UiTheme.html("Could not play \"" + humanize(lineId) + "\" — " + reason));
        playbackStatus.setForeground(UiTheme.BAD);
        applyEnablement();
    }

    /** Repaints every control from the current selection and playback state. */
    private void applyEnablement() {
        AuditionVoice selected = voiceList.getSelectedValue();
        useButton.setEnabled(selected != null);
        String whyNotUsable = selected == null ? "select a voice above first" : "";
        useReason.setText(whyNotUsable);
        // useReason is elastic (UiTheme.elastic), which sets its tooltip once at
        // construction from the text at that moment — empty, since nothing has been
        // selected yet. Refreshed here on every call, the same as VoicePanel's reason
        // labels, so a sentence Swing ellipsises at a narrow width still reads in full
        // on hover instead of showing whatever the tooltip happened to be at startup.
        useReason.setToolTipText(whyNotUsable.isEmpty() ? null : whyNotUsable);

        String reason;
        if (voices.isEmpty()) {
            reason = "nothing to audition";
        } else if (selected == null) {
            reason = "select a voice above to hear its lines";
        } else if (playing) {
            reason = "waiting for the line already playing";
        } else {
            reason = "";
        }
        linesReason.setText(reason);
        for (var entry : lineButtons.entrySet()) {
            boolean hasLine = selected != null && selected.lines().containsKey(entry.getKey());
            entry.getValue().setEnabled(reason.isEmpty() && hasLine);
        }
        revalidate();
        repaint();
    }

    /** {@code "one-test-failed"} to {@code "One test failed"}. */
    private static String humanize(String lineId) {
        String spaced = lineId.replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** {@code "Voice A"} … {@code "Voice Z"}, then {@code "Voice 27"} rather than repeat. */
    private static String blindLabelFor(int index) {
        return index < 26 ? "Voice " + (char) ('A' + index) : "Voice " + (index + 1);
    }

    private final class VoiceCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            AuditionVoice voice = (AuditionVoice) value;
            String text = revealToggle.isSelected()
                ? voice.name() + " \u2014 " + voice.release()
                : blindLabelFor(index);
            Component cell = super.getListCellRendererComponent(
                list, text, index, isSelected, cellHasFocus);
            setBorder(UiTheme.pad(UiTheme.GAP));
            return cell;
        }
    }

    /** A row of controls, left-packed, that gives its spare width away to nothing. */
    private static JPanel row(JComponent... parts) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                panel.add(Box.createHorizontalStrut(UiTheme.GAP));
            }
            parts[i].setAlignmentY(Component.CENTER_ALIGNMENT);
            panel.add(parts[i]);
        }
        panel.add(Box.createHorizontalGlue());
        return panel;
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
