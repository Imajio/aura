package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first section: what Aura is, without being asked.
 *
 * <p>Four questions a person has when something is not working, answered in four
 * cards - is the sidecar alive, what can it do, what is missing before voice
 * works, and where is the log. Anything missing is a line with a button beside it
 * that goes to the section which fixes it, because a screen that reports a
 * problem and then offers no way out is a dead end with good manners.
 *
 * <p>Nothing here reads the filesystem. Whether the wake-word model exists is the
 * sidecar's answer to {@code voice.status}, not this panel's own {@code
 * Files.exists} - two readers of one truth is how they come to disagree, and the
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
    private final Path logDir;
    private final Predicate<String> hasSection;
    private final Consumer<String> goToSection;

    private final JPanel column = new JPanel();

    // Built once here and only reparented by rebuild(), never recreated: a
    // button JetControls builds registers its own Theme.onChange listener,
    // and a factory method called fresh on every sidecar event leaked one
    // per rebuild with nothing to release it - measured at +150 over thirty
    // voice.status events before this fix. JetControls's own removeNotify
    // and addNotify hooks do not save this panel by themselves: they only
    // fire once a control has been part of a displayable hierarchy, and
    // rebuild()'s column.removeAll() runs long before AuraWindow ever shows
    // its frame, as well as on every event afterwards. Caching sidesteps
    // displayability entirely - a button that is never recreated has no new
    // listener to leak, shown or not.
    private final JButton speakerModelSetupButton;
    private final JButton referenceSetupButton;
    private final JButton wakeModelSetupButton;
    private final JButton listeningSetupButton;
    private final JButton logFolderButton;

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

    StatusPanel(Consumer<Map<String, Object>> toSidecar, Path logDir,
                Predicate<String> hasSection, Consumer<String> goToSection) {
        this.toSidecar = toSidecar;
        this.logDir = logDir;
        this.hasSection = hasSection;
        this.goToSection = goToSection;

        speakerModelSetupButton = newSetupButton();
        referenceSetupButton = newSetupButton();
        wakeModelSetupButton = newSetupButton();
        listeningSetupButton = newSetupButton();
        logFolderButton = newLogFolderButton();

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(UiTheme.pad(UiTheme.WIDE));

        JScrollPane scroll = JetControls.scrollPane(new ContentPane(column),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);

        // A plain JPanel's and a JViewport's background both hold whatever colour
        // they were given, the same as a Border - neither has a paintComponent this
        // class can read a token from, so both are re-applied from this listener
        // instead, the way VoiceChoicePanel's own canvas is. Called once right here
        // as well as registered, the way JetControls.list, Button and
        // PlaceholderField all do: AuraWindow builds this panel before it calls
        // Theme.install, so without the immediate call this panel would keep the
        // look and feel's cached background until the first switch.
        Runnable refreshCanvas = () -> {
            setBackground(UiTheme.canvas());
            scroll.getViewport().setBackground(UiTheme.canvas());
        };
        Theme.onChange(refreshCanvas);
        refreshCanvas.run();

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
     * handle on every label it might later have to change - which is how a card
     * ends up showing two states at once because one of the handles was missed.
     * The five buttons are the deliberate exception: {@link #speakerModelSetupButton}
     * and its four siblings are built once by the constructor and only placed into
     * whichever new card wants them here, never recreated - see the field comment
     * for why.
     */
    private void rebuild() {
        // A cached button the new cards do not place would otherwise keep a
        // parent pointer into a card that is about to be thrown away.
        for (JButton button : List.of(speakerModelSetupButton, referenceSetupButton,
                wakeModelSetupButton, listeningSetupButton, logFolderButton)) {
            Container parent = button.getParent();
            if (parent != null) {
                parent.remove(button);
            }
        }
        refreshSetupButtons();
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
     * The section's title, on its own line above the cards.
     *
     * <p>Used to carry {@code Stop agent} as well, at the top rather than under
     * four cards of reading: the owner's complaint about the tray was that
     * acting on what it showed meant hunting for an icon. The button now lives in
     * the Tasks section, beside the task it stops, which is its real home; this
     * header is what is left once it moved.
     */
    private JComponent header() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(UiTheme.title("Status"));
        row.add(Box.createHorizontalGlue());
        // The header's height is settled the moment it is built, so the theme's
        // own capper says it. A card cannot use this - its height changes with
        // what the sidecar last said - which is why Card overrides
        // getMaximumSize instead of calling here.
        return UiTheme.capped(row, row.getPreferredSize().height);
    }

    private JComponent sidecarCard() {
        Card card = new Card("Sidecar");
        if (sidecarReady) {
            card.line("Process", UiTheme.status("running", "success"), null);
            card.line("Devices", UiTheme.body(device("NPU", npu) + "   " + device("GPU", gpu)),
                null);
            // One row, not three. "What can it do" is a single question, and
            // three rows of one word each turn a glance into a read.
            card.line("Models", models(), null);
        } else {
            card.line("Process", UiTheme.status("not running", "warning"), null);
            card.note("Aura still dispatches typed tasks and gates tool calls without it. "
                + "What it cannot do is hear or speak.");
        }
        return card;
    }

    private JComponent voiceCard() {
        Card card = new Card("Voice");
        if (voiceUnavailable) {
            card.line("Voice support", UiTheme.status("not in this build", "warning"), null);
            card.note("The sidecar that is running has no voice support compiled in.");
            return card;
        }
        if (!voiceAnswered) {
            card.line("Voice setup", UiTheme.status("not known yet", "text.secondary"), null);
            card.note(sidecarReady
                ? "Waiting for the sidecar to answer."
                : "Nothing to ask until the sidecar is running.");
            return card;
        }
        artefact(card, "Speaker model", speakerModel, "trained", -1, speakerModelSetupButton);
        artefact(card, "Voice reference", reference, "recorded", referenceTakes, referenceSetupButton);
        artefact(card, "Wake-word model", wakeModel, "trained", wakeTakes, wakeModelSetupButton);
        return card;
    }

    /**
     * One line of the Voice card: what it is, whether it is there, and either the
     * recordings behind it or the way to make it.
     *
     * <p>The take count rides in the third column when the artefact exists, and
     * drops to a line of its own only when it does not - where it stops being
     * trivia and starts being the answer to "how far off am I?". Pass a negative
     * count for an artefact that has no recordings behind it. {@code setupButton}
     * is one of this panel's own cached buttons, placed into this row rather than
     * built for it.
     */
    private void artefact(Card card, String name, boolean present, String yes, int count, JButton setupButton) {
        card.line(name,
            present ? UiTheme.status(yes, "success") : UiTheme.status("missing", "warning"),
            present ? (count < 0 ? null : UiTheme.hint(takes(count))) : setupButton);
        if (!present && count > 0) {
            card.note(takes(count) + " so far - not enough to train on.");
        }
    }

    private JComponent listeningCard() {
        Card card = new Card("Listening");
        card.line("Microphone",
            listening ? UiTheme.status("on", "success")
                      : UiTheme.status("off", "text.secondary"),
            null);
        if (listening) {
            card.note("Aura is waiting for the wake word. Nothing leaves this machine.");
        } else if (voiceAnswered && !wakeModel) {
            card.line("Why not", UiTheme.body("there is no wake-word model to listen for"),
                listeningSetupButton);
        } else {
            // The switch itself lives in the Voice section, beside the model it
            // listens for, and this card sends people there instead of growing a
            // second copy of it. Until protocol.py's `configure` learned to read
            // `listen` there was no switch to send anyone to, and this card
            // could only name a line of config.yaml.
            card.line("Why not", UiTheme.body("it has not been switched on"), listeningSetupButton);
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
        card.line("Folder", UiTheme.elastic(path), logFolderButton);
        card.note("Everything Aura did, including what it refused and why.");
        return card;
    }

    /** The three model slots, each with its own state colour. */
    private JComponent models() {
        SlotFlow row = new SlotFlow();
        row.addSlot("Speech to text", modelState(stt));
        row.addSlot("Narrator", modelState(slm));
        row.addSlot("Text to speech", modelState(tts));
        return row;
    }

    /**
     * The Models row: its slots on one line when the card has the width for
     * them, flowing onto further lines when it does not, every slot whole.
     *
     * <p>All six labels used to share one {@code BoxLayout} row, each made
     * shrinkable, so a row short of width shrank every one of them in
     * proportion: within about 50 px of the window's minimum width every name
     * and every state word was cut to an ellipsis, "lazy" included. The one
     * line's width also pushed the card's grid onto its minimum sizes, which
     * is what moved this card's state column left of every other card's.
     * Asking only for the widest slot's width keeps the grid on its preferred
     * sizes.
     *
     * <p>How many lines fit is known only once the width is, and the layout
     * that sets the width asks for the height first. So the height reported
     * is the one the width from the last layout needs, and a layout whose new
     * width needs a different number of lines asks for one more pass. That
     * pass settles it: the width this row is given does not depend on its
     * height.
     */
    private static final class SlotFlow extends JPanel {

        private JLabel firstName;
        private boolean passPending;

        SlotFlow() {
            super(null);
            setOpaque(false);
        }

        /**
         * One model's name and state, kept together. Only the state gives way,
         * and only on a line narrower than this one slot: it is the half the
         * sidecar names, and could be longer than anything this panel chose.
         */
        void addSlot(String name, JLabel state) {
            JLabel label = UiTheme.hint(name);
            JPanel slot = new JPanel();
            slot.setLayout(new BoxLayout(slot, BoxLayout.X_AXIS));
            slot.setOpaque(false);
            slot.add(label);
            slot.add(Box.createHorizontalStrut(UiTheme.GAP));
            slot.add(UiTheme.elastic(state));
            add(slot);
            if (firstName == null) {
                firstName = label;
            }
        }

        /**
         * The first line's baseline, which is where the card puts the row's
         * name. A slot's name and state share one font size, so the name sits
         * at the top of its slot and the first slot at the top of this row.
         */
        @Override
        public int getBaseline(int width, int height) {
            if (firstName == null) {
                return -1;
            }
            Dimension size = firstName.getPreferredSize();
            return firstName.getBaseline(size.width, size.height);
        }

        @Override
        public BaselineResizeBehavior getBaselineResizeBehavior() {
            return BaselineResizeBehavior.CONSTANT_ASCENT;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(widest(false), lines(getWidth() > 0 ? getWidth() : Integer.MAX_VALUE, false));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(widest(true), getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            int needed = lines(getWidth(), true);
            if (needed != getHeight() && !passPending) {
                passPending = true;
                SwingUtilities.invokeLater(() -> {
                    passPending = false;
                    revalidate();
                });
            }
        }

        private int widest(boolean minimum) {
            int widest = 0;
            for (Component slot : getComponents()) {
                widest = Math.max(widest, (minimum ? slot.getMinimumSize() : slot.getPreferredSize()).width);
            }
            return widest;
        }

        /**
         * Places the slots in lines no wider than {@code width}, or only
         * measures them, and returns the height they take. A slot wider than
         * a whole line gets the line.
         */
        private int lines(int width, boolean place) {
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            for (Component slot : getComponents()) {
                Dimension size = slot.getPreferredSize();
                if (x > 0 && (long) x + UiTheme.WIDE + size.width > width) {
                    x = 0;
                    y += lineHeight + UiTheme.TIGHT;
                    lineHeight = 0;
                } else if (x > 0) {
                    x += UiTheme.WIDE;
                }
                if (place) {
                    slot.setBounds(x, y, Math.max(0, Math.min(size.width, width - x)), size.height);
                }
                x += size.width;
                lineHeight = Math.max(lineHeight, size.height);
            }
            return y + lineHeight;
        }
    }

    /**
     * Builds one of the four cached buttons that go to the section which fixes
     * voice setup. Its enabled state is not decided here: {@code AuraWindow} adds
     * this panel before it adds the Voice section, so {@link #hasSection} would
     * always answer false the one time a constructor could ask it. {@link
     * #refreshSetupButtons()} asks instead, once per {@link #rebuild()}, by which
     * time the answer is settled.
     */
    private JButton newSetupButton() {
        JButton button = JetControls.button("Set this up");
        button.addActionListener(e -> goToSection.accept(AuraWindow.VOICE_SECTION));
        return button;
    }

    /**
     * Brings every cached "Set this up" button's enabled state and tooltip up to
     * date. Run once per {@link #rebuild()} rather than once per button use - all
     * four ask {@link #hasSection} the same question, and the answer cannot
     * differ between them.
     */
    private void refreshSetupButtons() {
        boolean available = hasSection.test(AuraWindow.VOICE_SECTION);
        String unavailableReason =
            "The " + AuraWindow.VOICE_SECTION + " section is not in this build yet.";
        refreshSetupButton(speakerModelSetupButton, available, unavailableReason);
        refreshSetupButton(referenceSetupButton, available, unavailableReason);
        refreshSetupButton(wakeModelSetupButton, available, unavailableReason);
        refreshSetupButton(listeningSetupButton, available, unavailableReason);
    }

    private static void refreshSetupButton(JButton button, boolean available, String unavailableReason) {
        button.setEnabled(available);
        button.setToolTipText(available ? null : unavailableReason);
    }

    private JButton newLogFolderButton() {
        JButton button = JetControls.button("Open log folder");
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

    private static JLabel modelState(String state) {
        String value = state == null || state.isBlank() ? "unknown" : state;
        String token = switch (value.toLowerCase(Locale.ROOT)) {
            case "loaded" -> "success";
            // Loads on first use: not a fault to fix and not something to press,
            // so plain secondary text rather than a state colour or the accent.
            case "lazy" -> "text.secondary";
            case "absent" -> "warning";
            default -> "text.secondary";
        };
        return UiTheme.status(value, token);
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
