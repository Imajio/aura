package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first section: what Aura is, without being asked.
 *
 * <p>Four questions a person has when something is not working, answered in four
 * cards — is the sidecar alive, what can it do, what is missing before voice
 * works, and where is the log. Anything missing is a line with a button beside it
 * that goes to the section which fixes it, because a screen that reports a
 * problem and then offers no way out is a dead end with good manners.
 *
 * <p>Nothing here reads the filesystem. Whether the wake-word model exists is the
 * sidecar's answer to {@code voice.status}, not this panel's own {@code
 * Files.exists} — two readers of one truth is how they come to disagree, and the
 * one that is wrong is always the one the user is looking at. The question is
 * asked when the window opens and again after {@code train.done} or {@code
 * record.done}, the two events that change what is on disk.
 *
 * <p>Every method here expects to be on the event dispatch thread; {@link
 * AuraWindow} puts sidecar events onto it before fanning them out.
 */
public final class StatusPanel extends JPanel implements Consumer<SidecarEvent> {

    private static final Logger log = LoggerFactory.getLogger(StatusPanel.class);

    private final Consumer<Map<String, Object>> toSidecar;
    private final Runnable onStopAgent;
    private final Path logDir;
    private final Predicate<String> hasSection;
    private final Consumer<String> goToSection;

    private final JPanel column = new JPanel();

    // What the sidecar has told us so far. Every field starts at the value that
    // means "it has not said yet", so the first paint is honest about knowing
    // nothing rather than guessing at zeroes.
    private boolean sidecarReady;
    private boolean npu;
    private boolean gpu;
    private String stt = "";
    private String slm = "";
    private String tts = "";
    private boolean voiceAnswered;
    private boolean voiceUnavailable;
    private boolean speakerModel;
    private boolean reference;
    private boolean wakeModel;
    private int referenceTakes;
    private int wakeTakes;
    private boolean listening;

    StatusPanel(Consumer<Map<String, Object>> toSidecar, Runnable onStopAgent, Path logDir,
                Predicate<String> hasSection, Consumer<String> goToSection) {
        this.toSidecar = toSidecar;
        this.onStopAgent = onStopAgent;
        this.logDir = logDir;
        this.hasSection = hasSection;
        this.goToSection = goToSection;

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(UiTheme.pad(UiTheme.WIDE));

        JScrollPane scroll = new JScrollPane(new ContentPane(column),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(UiTheme.CANVAS);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        setBackground(UiTheme.CANVAS);
        add(scroll, BorderLayout.CENTER);

        rebuild();
    }

    /** Asks the sidecar what voice artefacts exist. Safe with no sidecar: it no-ops. */
    void refresh() {
        try {
            toSidecar.accept(Map.of("id", "window.voice.status", "cmd", "voice.status"));
        } catch (Exception e) {
            // A sidecar that has died takes the write down with it. The card
            // already says what it knows; a dialog would add nothing.
            log.debug("could not ask the sidecar for voice.status", e);
        }
    }

    @Override
    public void accept(SidecarEvent event) {
        switch (event.kind()) {
            case "ready" -> {
                sidecarReady = true;
                JsonNode devices = event.body().path("devices");
                npu = devices.path("npu").asBoolean();
                gpu = devices.path("gpu").asBoolean();
                JsonNode models = event.body().path("models");
                stt = models.path("stt").asText("");
                slm = models.path("slm").asText("");
                tts = models.path("tts").asText("");
                rebuild();
                // The window may have opened before the sidecar was up, in which
                // case the first voice.status went nowhere. Ask again now that
                // there is certainly something listening.
                refresh();
            }
            case "voice.status" -> {
                voiceAnswered = true;
                voiceUnavailable = false;
                speakerModel = event.flag("speakerModel");
                reference = event.flag("reference");
                wakeModel = event.flag("wakeModel");
                referenceTakes = (int) event.number("referenceTakes");
                wakeTakes = (int) event.number("wakeTakes");
                listening = event.flag("listening");
                rebuild();
            }
            // The two events that change what is on disk. Nothing else can make
            // a model appear or a take be added, so nothing else needs to ask.
            case "train.done", "record.done" -> refresh();
            case "error" -> {
                if ("VOICE_UNAVAILABLE".equals(event.text("code"))) {
                    // A build with no voice support at all. Saying so beats
                    // waiting forever for an answer that will never come.
                    voiceUnavailable = true;
                    rebuild();
                }
            }
            default -> { }
        }
    }

    /**
     * Repaints every card from the fields above.
     *
     * <p>Rebuilt wholesale rather than patched line by line. Four cards of labels
     * cost nothing to recreate, and a panel that edits itself in place needs a
     * handle on every label it might later have to change — which is how a card
     * ends up showing two states at once because one of the handles was missed.
     */
    private void rebuild() {
        column.removeAll();
        column.add(header());
        column.add(Box.createVerticalStrut(UiTheme.TIGHT));
        column.add(leftAligned(UiTheme.hint(
            "What Aura can do right now, and what is missing before it can do the rest.")));
        column.add(Box.createVerticalStrut(UiTheme.WIDE));

        column.add(sidecarCard());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(voiceCard());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(listeningCard());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(logCard());

        column.revalidate();
        column.repaint();
    }

    /**
     * The section's title and the one thing it lets a person do.
     *
     * <p>Stopping a runaway agent lives here rather than in a card of its own,
     * and at the top rather than under four cards of reading. The owner's
     * complaint about the tray was that acting on what it showed meant hunting
     * for an icon; putting the action out of sight below the fold of the window
     * that replaced it would repeat the mistake in a bigger space.
     */
    private JComponent header() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton stop = button("Stop agent");
        stop.setToolTipText("Closes the running agent session. The tray has the same button.");
        stop.addActionListener(e -> onStopAgent.run());

        row.add(UiTheme.title("Status"));
        row.add(Box.createHorizontalGlue());
        row.add(stop);
        // The header's height is settled the moment it is built, so the theme's
        // own capper says it. A card cannot use this — its height changes with
        // what the sidecar last said — which is why Card overrides
        // getMaximumSize instead of calling here.
        return UiTheme.capped(row, row.getPreferredSize().height);
    }

    private JComponent sidecarCard() {
        Card card = new Card("Sidecar");
        if (sidecarReady) {
            card.line("Process", UiTheme.status("running", UiTheme.GOOD), null);
            card.line("Devices", UiTheme.body(device("NPU", npu) + "   " + device("GPU", gpu)),
                null);
            // One row, not three. "What can it do" is a single question, and
            // three rows of one word each turn a glance into a read.
            card.line("Models", models(), null);
        } else {
            card.line("Process", UiTheme.status("not running", UiTheme.WARN), null);
            card.note("Aura still dispatches typed tasks and gates tool calls without it. "
                + "What it cannot do is hear or speak.");
        }
        return card;
    }

    private JComponent voiceCard() {
        Card card = new Card("Voice");
        if (voiceUnavailable) {
            card.line("Voice support", UiTheme.status("not in this build", UiTheme.WARN), null);
            card.note("The sidecar that is running has no voice support compiled in.");
            return card;
        }
        if (!voiceAnswered) {
            card.line("Voice setup", UiTheme.status("not known yet", UiTheme.MUTED), null);
            card.note(sidecarReady
                ? "Waiting for the sidecar to answer."
                : "Nothing to ask until the sidecar is running.");
            return card;
        }
        artefact(card, "Speaker model", speakerModel, "trained", -1);
        artefact(card, "Voice reference", reference, "recorded", referenceTakes);
        artefact(card, "Wake-word model", wakeModel, "trained", wakeTakes);
        return card;
    }

    /**
     * One line of the Voice card: what it is, whether it is there, and either the
     * recordings behind it or the way to make it.
     *
     * <p>The take count rides in the third column when the artefact exists, and
     * drops to a line of its own only when it does not — where it stops being
     * trivia and starts being the answer to "how far off am I?". Pass a negative
     * count for an artefact that has no recordings behind it.
     */
    private void artefact(Card card, String name, boolean present, String yes, int count) {
        card.line(name,
            present ? UiTheme.status(yes, UiTheme.GOOD) : UiTheme.status("missing", UiTheme.WARN),
            present ? (count < 0 ? null : UiTheme.hint(takes(count))) : setUpButton());
        if (!present && count > 0) {
            card.note(takes(count) + " so far — not enough to train on.");
        }
    }

    private JComponent listeningCard() {
        Card card = new Card("Listening");
        card.line("Microphone",
            listening ? UiTheme.status("on", UiTheme.GOOD)
                      : UiTheme.status("off", UiTheme.MUTED),
            null);
        if (listening) {
            card.note("Aura is waiting for the wake word. Nothing leaves this machine.");
        } else if (voiceAnswered && !wakeModel) {
            card.line("Why not", UiTheme.body("there is no wake-word model to listen for"),
                setUpButton());
        } else {
            // The switch itself lives in the Voice section, beside the model it
            // listens for, and this card sends people there instead of growing a
            // second copy of it. Until protocol.py's `configure` learned to read
            // `listen` there was no switch to send anyone to, and this card
            // could only name a line of config.yaml.
            card.line("Why not", UiTheme.body("it has not been switched on"), setUpButton());
            card.note("Switching it on in the Voice section takes effect at once. "
                + "listen: true in %APPDATA%\\Aura\\config.yaml is what starts it with "
                + "Aura, for a machine that is left running.");
        }
        return card;
    }

    private JComponent logCard() {
        Card card = new Card("Log");
        JLabel path = UiTheme.body(logDir.toString());
        path.setFont(UiTheme.mono());
        card.line("Folder", UiTheme.elastic(path), openLogButton());
        card.note("Everything Aura did, including what it refused and why.");
        return card;
    }

    /** The three model slots on one line, each with its own state colour. */
    private JComponent models() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        slot(row, "Speech to text", stt, true);
        slot(row, "Narrator", slm, false);
        slot(row, "Text to speech", tts, false);
        return row;
    }

    private void slot(JPanel row, String name, String state, boolean first) {
        if (!first) {
            row.add(Box.createHorizontalStrut(UiTheme.WIDE));
        }
        // The slot's own name is elastic too. Three names and three states on one
        // line is the widest row on the page, and a row that cannot shrink is a
        // row that shoves the whole grid off the left edge at a narrow window —
        // the state alone being shrinkable would not save it.
        row.add(UiTheme.elastic(UiTheme.hint(name)));
        row.add(Box.createHorizontalStrut(UiTheme.GAP));
        row.add(UiTheme.elastic(modelState(state)));
    }

    /** Goes to the section that fixes voice setup, or stays disabled until it exists. */
    private JButton setUpButton() {
        JButton button = button("Set this up");
        boolean available = hasSection.test(AuraWindow.VOICE_SECTION);
        button.setEnabled(available);
        button.setToolTipText(available ? null
            : "The " + AuraWindow.VOICE_SECTION + " section is not in this build yet.");
        button.addActionListener(e -> goToSection.accept(AuraWindow.VOICE_SECTION));
        return button;
    }

    private JButton openLogButton() {
        JButton button = button("Open log folder");
        button.addActionListener(e -> {
            // Same best-effort open as the tray's menu item, for the same reason:
            // failing to open a folder does not deserve a dialog, but a silent
            // no-op looks like a broken button.
            try {
                Files.createDirectories(logDir);
                Desktop.getDesktop().open(logDir.toFile());
            } catch (IOException | UnsupportedOperationException | IllegalStateException ex) {
                log.warn("could not open the log folder {}", logDir, ex);
            }
        });
        return button;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(UiTheme.body());
        button.setFocusPainted(false);
        return button;
    }

    private static JLabel modelState(String state) {
        String value = state == null || state.isBlank() ? "unknown" : state;
        Color colour = switch (value.toLowerCase(Locale.ROOT)) {
            case "loaded" -> UiTheme.GOOD;
            case "lazy" -> UiTheme.ACCENT;
            case "absent" -> UiTheme.WARN;
            default -> UiTheme.MUTED;
        };
        return UiTheme.status(value, colour);
    }

    private static String device(String name, boolean present) {
        return name + ": " + (present ? "yes" : "no");
    }

    private static String takes(int count) {
        return count == 1 ? "1 recording" : count + " recordings";
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
