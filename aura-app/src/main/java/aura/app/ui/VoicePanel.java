package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teaching Aura a voice, by button.
 *
 * <p>Everything this section does was already possible from a terminal, with the
 * repository checked out, a virtual environment activated and two scripts run in
 * the right order. That is not a thing anybody does twice. The whole point of the
 * window is that the owner can improve the model whenever they feel like it -
 * record four more takes on a Tuesday, train again, see the two numbers - and
 * something done on a whim has to be reachable in one click.
 *
 * <h2>What is on screen</h2>
 *
 * <p>Two halves of the same shape. <b>Your voice</b> is the reference the speaker
 * check compares against: record ordinary speech, then average the takes into one
 * embedding. <b>Your wake word</b> is the model that decides whether Aura was
 * addressed: record the word, then fit it against audio that is not it. Each half
 * says how many takes are on disk, offers a spinner and a {@code Record} button,
 * and offers the thing that turns takes into an artefact.
 *
 * <h2>Two rules</h2>
 *
 * <p><b>A button whose precondition is missing is dead, with the reason beside
 * it.</b> Not live and then failing: the sidecar knows perfectly well that
 * enrolment needs takes and that training needs five of them, and a panel that
 * lets a person press a button in order to be told so has chosen an error message
 * over an answer. The same applies while a command runs - the sidecar answers a
 * second one with {@code BUSY}, and a disabled button explains itself better than
 * an error does.
 *
 * <p><b>The microphone opens only after somebody has been told it will.</b>
 * {@code Record} arms; a second button, whose label says what it does, starts the
 * recording. Everything else in this product is undone by a microphone that opens
 * on its own.
 *
 * <h2>Threads and state</h2>
 *
 * <p>Nothing here waits. A command is sent and the method returns; every visible
 * change happens when an event arrives from the sidecar's reader thread, which
 * {@link AuraWindow} has already put on the event dispatch thread. The panel's
 * own idea of the world comes from the last {@code voice.status} and from nowhere
 * else - pressing the listening toggle does not make it show "on", because a
 * toggle that believes its own click is a toggle that lies the first time the
 * sidecar disagrees.
 *
 * <p>Built once and written to, rather than rebuilt like {@link StatusPanel}. A
 * rebuild would throw away a spinner the owner had just set to twenty.
 */
public final class VoicePanel extends JPanel implements Consumer<SidecarEvent> {

    private static final Logger log = LoggerFactory.getLogger(VoicePanel.class);

    /** Below this a take is too quiet to train on. record-voice-samples.py's number. */
    private static final double QUIET = 0.005;

    /**
     * Seconds between agreeing to record and the microphone opening.
     *
     * <p>The count runs here, before the command is sent, which is the only
     * place it can honestly run. Counting down from events that arrive after
     * {@code record} has already been sent would be narrating a fiction: by then
     * the sidecar is already opening the device. Counted this way the owner gets
     * the same three seconds the terminal recorder gave them to get ready, and
     * nothing is asked of the microphone until they are up.
     */
    private static final int COUNT_IN = 3;

    /**
     * Error codes the background listening thread raises on its own, with no
     * command to answer - {@code aura_speech/main.py}'s {@code _start_listening}
     * has no {@code message_id} in scope for any of them, since nothing sent a
     * message to reply to. Kept apart from every other error code, which does
     * carry one whenever it is answering something in flight.
     */
    private static final Set<String> LISTENING_SUBSYSTEM_CODES = Set.of(
        "MICROPHONE_FAILED", "RECOGNITION_FAILED", "NO_SPEAKER_REFERENCE", "NO_WAKE_WORD");

    private final Consumer<Map<String, Object>> toSidecar;
    private final int tickMillis;

    private final Section reference = new Section("reference", "Your voice", "Voice reference",
        "recorded", 3, 6,
        "Enrolment averages a few seconds of your ordinary speech into one reference, and "
            + "that reference is what tells you from whoever else is in the room. Speak a real "
            + "sentence in your normal voice - not the wake word, and not a careful recitation.",
        "Enrol from all takes", "enrol");

    private final Section wake = new Section("wake", "Your wake word", "Wake-word model",
        "trained", 20, 2,
        "Say “Aura”, the way you would actually say it rather than the way you would "
            + "read it aloud. Vary it: closer and further from the laptop, sitting and standing, "
            + "quietly, in a hurry. Twenty identical takes teach one mood and miss every other.",
        "Train the wake word", "train.wake");

    private final JCheckBox listenToggle = JetControls.checkBox("Listen for the wake word");
    private final JLabel listenState = UiTheme.status("off", "text.secondary");
    private final JLabel listenReason = UiTheme.fitted("", "text.secondary");
    private final JLabel listenReport = UiTheme.wrapped("");
    private String listenMessage = "";

    // What the sidecar last said. Every field starts where "it has not said yet"
    // leaves it, so the first paint offers nothing rather than guessing.
    private boolean answered;
    private boolean unavailable;
    private int referenceTakes;
    private int wakeTakes;
    private boolean hasReference;
    private boolean hasWakeModel;
    private boolean hasSpeakerModel;
    private boolean featureModels;
    private int negatives;
    private boolean listening;

    // The command in flight, or null. Correlation ids are unique per press so
    // that a late answer to a command that has already ended cannot end the next
    // one; `pendingSection` is where that command's failure gets reported, and is
    // null when the listening toggle is the thing waiting.
    private String pendingId;
    private Section pendingSection;
    private int commands;

    VoicePanel(Consumer<Map<String, Object>> toSidecar) {
        this(toSidecar, 1000);
    }

    /**
     * The same panel with a count-in that ticks faster, for tests.
     *
     * <p>The number of ticks is fixed at {@link #COUNT_IN}; only how long one
     * lasts is open. A test that had to sit through three real seconds would
     * either be slow or would skip the count-in altogether and stop covering the
     * path the owner actually walks.
     */
    VoicePanel(Consumer<Map<String, Object>> toSidecar, int tickMillis) {
        this.toSidecar = toSidecar;
        this.tickMillis = tickMillis;

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(UiTheme.pad(UiTheme.WIDE));

        column.add(leftAligned(UiTheme.title("Voice setup")));
        column.add(Box.createVerticalStrut(UiTheme.TIGHT));
        column.add(leftAligned(UiTheme.wrapped(
            "Teach Aura your voice and your wake word, as often as you like. "
                + "Every recording stays on this machine.")));
        column.add(Box.createVerticalStrut(UiTheme.WIDE));

        column.add(reference.card());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(wake.card());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(listeningCard());

        ContentPane.page(this, column);

        applyState();
    }

    /**
     * Asks the sidecar what is on disk. Safe with no sidecar: it no-ops.
     *
     * <p>Deliberately not a command in flight. The window asks this every time it
     * opens, and treating it like {@code record} would bring the section up with
     * every button dead, waiting for an answer that says nothing about whether a
     * button may be pressed.
     */
    void refresh() {
        try {
            toSidecar.accept(Map.of("id", "voice.panel.status", "cmd", "voice.status"));
        } catch (Exception e) {
            log.debug("could not ask the sidecar for voice.status", e);
        }
    }

    @Override
    public void accept(SidecarEvent event) {
        switch (event.kind()) {
            // The sidecar may have started after the window did, in which case
            // the first question went nowhere. Ask again now something is there.
            case "ready" -> refresh();
            case "voice.status" -> {
                answered = true;
                unavailable = false;
                referenceTakes = (int) event.number("referenceTakes");
                wakeTakes = (int) event.number("wakeTakes");
                hasReference = event.flag("reference");
                hasWakeModel = event.flag("wakeModel");
                hasSpeakerModel = event.flag("speakerModel");
                featureModels = event.flag("featureModels");
                negatives = (int) event.number("negatives");
                listening = event.flag("listening");
                // This is how the listening toggle finishes: the sidecar answers
                // a configure with a status, and that status is the only thing
                // that moves the switch.
                finish(event.text("for"));
                applyState();
            }
            case "record.started" -> {
                Section section = sectionForKind(event.text("kind"));
                if (section != null) {
                    // Provisional on purpose. protocol.py emits record.started
                    // from the loop thread, before the worker that will pause
                    // listening and open the device has even been started - and
                    // if that pause fails the device never opens for this take
                    // at all. The panel says it is open only when a take proves
                    // it, on the first record.take below.
                    section.startProgress((int) event.number("takes"),
                        "opening the microphone…");
                    applyState();
                }
            }
            case "record.take" -> {
                Section section = sectionForKind(event.text("kind"));
                if (section != null) {
                    section.take((int) event.number("number"), event.number("level"));
                    applyState();
                }
            }
            case "record.done" -> {
                Section section = sectionForKind(event.text("kind"));
                if (section != null) {
                    section.recorded((int) event.number("takes"));
                }
                finish(event.text("for"));
                applyState();
                // The take counts on screen are the sidecar's count of files, not
                // this panel's arithmetic, so they come from asking again.
                refresh();
            }
            case "train.progress" -> {
                Section section = sectionForCommand(event.text("cmd"));
                if (section != null) {
                    section.progressed(event.text("stage"), (int) event.number("done"),
                        (int) event.number("total"), event.text("detail"));
                    applyState();
                }
            }
            case "train.done" -> {
                Section section = sectionForCommand(event.text("cmd"));
                if (section != null) {
                    section.trained(event);
                }
                finish(event.text("for"));
                applyState();
                refresh();
            }
            case "error" -> failed(event);
            default -> { }
        }
    }

    /**
     * An error ends whatever it answers, and says so where that command lived.
     *
     * <p>The correlation id is what makes this safe. A narration that failed
     * halfway through a training run also arrives as an {@code error}, and a
     * panel that treated every error as the end of its own command would set its
     * buttons live while the sidecar was still busy - straight into {@code BUSY}
     * on the next press. An error carrying no id at all is normally the
     * exception: nothing else will arrive to end the command, so it ends it.
     *
     * <p>{@link #LISTENING_SUBSYSTEM_CODES} is the exception to that exception.
     * {@code MICROPHONE_FAILED} and {@code RECOGNITION_FAILED} come from the
     * background listening thread, which neither {@code enrol} nor {@code
     * train.wake} pauses - so with listening on, a recognition hiccup can land
     * mid-training, carrying no id for the same reason those two never will:
     * nothing sent a message for it to answer. Reading that bare {@code for} as
     * "nothing else will end this, so it must" would paint a still-running
     * training command as failed over an error that has nothing to do with it.
     * These four are reported in the Listening card instead, where they belong.
     */
    private void failed(SidecarEvent event) {
        String code = event.text("code");
        if ("VOICE_UNAVAILABLE".equals(code)) {
            unavailable = true;
        }
        String answers = event.text("for");
        if (answers.isEmpty() && LISTENING_SUBSYSTEM_CODES.contains(code)) {
            String detail = event.text("detail");
            say(code + " - " + (detail.isEmpty() ? "the sidecar reported a listening problem"
                : detail));
            applyState();
            return;
        }
        if (pendingId == null || !(answers.isEmpty() || pendingId.equals(answers))) {
            applyState();
            return;
        }
        Section section = pendingSection;
        String detail = event.text("detail");
        String sentence = code + " - " + (detail.isEmpty() ? "the sidecar refused" : detail);
        if (section != null) {
            section.report(sentence, "error");
        } else {
            say(sentence);
        }
        pendingId = null;
        pendingSection = null;
        applyState();
    }

    /** Ends the command in flight if this event is the answer to it. */
    private void finish(String answers) {
        if (pendingId != null && pendingId.equals(answers)) {
            pendingId = null;
            pendingSection = null;
        }
    }

    private Section sectionForKind(String kind) {
        if (reference.kind.equals(kind)) {
            return reference;
        }
        return wake.kind.equals(kind) ? wake : null;
    }

    private Section sectionForCommand(String command) {
        if (reference.command.equals(command)) {
            return reference;
        }
        return wake.command.equals(command) ? wake : null;
    }

    /**
     * Sends one command and marks it in flight.
     *
     * <p>A send that throws - a sidecar that died since the last event - ends the
     * command here rather than leaving the section waiting for an answer no
     * process is left to give.
     */
    private void send(Map<String, Object> command, Section owner) {
        String id = "voice.panel." + command.get("cmd") + "." + (++commands);
        pendingId = id;
        pendingSection = owner;
        try {
            Map<String, Object> withId = new java.util.LinkedHashMap<>(command);
            withId.put("id", id);
            toSidecar.accept(Map.copyOf(withId));
        } catch (Exception e) {
            log.warn("could not send {} to the sidecar", command.get("cmd"), e);
            pendingId = null;
            pendingSection = null;
            String sentence = "The sidecar could not be reached. Nothing was started.";
            if (owner != null) {
                owner.report(sentence, "error");
            } else {
                say(sentence);
            }
        }
        applyState();
    }

    /** What went wrong with listening, in the listening card. */
    private void say(String sentence) {
        listenMessage = sentence;
        listenReport.setText(UiTheme.html(sentence));
        UiTheme.recolour(listenReport, "error");
        ContentPane.revealWhenLaidOut(listenReport);
    }

    private JComponent listeningCard() {
        Card card = new Card("Listening");
        card.line("Microphone", listenState, null);
        listenToggle.setName("voice.listen.toggle");
        listenToggle.addActionListener(e -> {
            // A JCheckBox flips itself before anybody is asked. Nothing puts it
            // back here: send() ends in applyState(), which is the single place
            // this switch's state is written, and it writes the sidecar's last
            // answer. Undoing the flip here as well would be a second guard that
            // masks a break in the first, and then no test could say which one
            // was holding the line.
            send(Map.of("cmd", "configure", "listen", !listening), null);
        });
        listenReason.setName("voice.listen.reason");
        listenState.setName("voice.listen.state");
        listenReport.setName("voice.listen.report");
        card.row(row(listenToggle, listenReason));
        card.note(listenReport);
        card.note("Listening runs the wake word against the microphone and nothing else: "
            + "audio is dropped as it arrives until the word is heard. It stays off until "
            + "switched on here, and switching it on takes effect at once.");
        return card;
    }

    /** Everything the two halves have in common, twice over. */
    private final class Section {

        private final String kind;
        private final String command;
        private final String artefactName;
        private final String artefactWord;
        private final String heading;
        private final String explanation;
        private final String actionLabel;
        private final int defaultTakes;
        private final int secondsPerTake;

        private final JSpinner takes;
        private final JButton record = JetControls.button("Record takes…");
        private final JButton confirm;
        private final JButton cancel = JetControls.button("Cancel");
        private final JButton action;
        private final JLabel takesValue = UiTheme.status("", "text.secondary");
        private final JLabel artefactValue = UiTheme.status("", "text.secondary");
        private final JLabel reason = UiTheme.fitted("", "text.secondary");
        private final JLabel recordReason = UiTheme.fitted("", "text.secondary");
        private final JLabel warning = UiTheme.wrapped("", "warning");
        private final JProgressBar progress = JetControls.progressBar();
        private final JLabel report = UiTheme.wrapped("");
        private final JPanel armRow;
        private JComponent cardPanel;

        private boolean armed;
        private int counting;
        private final Timer countIn;
        private final List<String> lines = new ArrayList<>();
        private int quiet;

        Section(String kind, String heading, String artefactName, String artefactWord,
                int defaultTakes, int secondsPerTake, String explanation, String actionLabel,
                String command) {
            this.kind = kind;
            this.heading = heading;
            this.artefactName = artefactName;
            this.artefactWord = artefactWord;
            this.defaultTakes = defaultTakes;
            this.secondsPerTake = secondsPerTake;
            this.explanation = explanation;
            this.actionLabel = actionLabel;
            this.command = command;

            takes = JetControls.spinner(new SpinnerNumberModel(defaultTakes, 1, 50, 1));
            // A Swing Timer, so every tick lands on the event dispatch thread
            // like everything else that touches these components.
            countIn = new Timer(1000, e -> tick());
            confirm = JetControls.button("Open the microphone and record");
            // The major action of its card, and the only accent-filled button
            // in it: confirm, beside it while armed, is deliberately neutral.
            action = JetControls.primaryButton(actionLabel);
            armRow = row(confirm, cancel);

            takes.setName("voice." + kind + ".spinner");
            record.setName("voice." + kind + ".record");
            confirm.setName("voice." + kind + ".confirm");
            cancel.setName("voice." + kind + ".cancel");
            action.setName("voice." + kind + ".action");
            reason.setName("voice." + kind + ".reason");
            recordReason.setName("voice." + kind + ".recordReason");
            warning.setName("voice." + kind + ".warning");
            takesValue.setName("voice." + kind + ".takes");
            artefactValue.setName("voice." + kind + ".artefact");
            progress.setName("voice." + kind + ".progress");
            report.setName("voice." + kind + ".report");

            // The warning names the number of takes, so changing the number
            // while it is on screen has to rewrite it. Through applyState, which
            // is the one place that sentence is written.
            takes.addChangeListener(e -> applyState());

            progress.setStringPainted(true);
            UiTheme.capped(progress, progress.getPreferredSize().height);

            record.addActionListener(e -> {
                armed = true;
                lines.clear();
                quiet = 0;
                report("", "text.secondary");
                applyState();
                // Into the row that just opened, not wherever Swing sends focus when
                // this button turns itself off: that was the primary action beside
                // it, where the next Space would start work. Cancel, because a
                // confirmation is safest answered with no.
                cancel.requestFocusInWindow();
            });
            // Cancels an arm and a count-in alike. Stopping the count is the
            // last moment at which nothing has been asked of the microphone,
            // so the button that offers it stays live for the whole count.
            cancel.addActionListener(e -> disarm());
            confirm.addActionListener(e -> {
                armed = false;
                counting = COUNT_IN;
                countIn.setInitialDelay(tickMillis);
                countIn.setDelay(tickMillis);
                countIn.restart();
                applyState();
                // This button turns itself off for the count; Cancel is the one
                // control still live in the row, and the way out of the count.
                cancel.requestFocusInWindow();
            });
            action.addActionListener(e -> {
                report("", "text.secondary");
                send(Map.of("cmd", command), this);
            });
        }

        JComponent card() {
            Card card = new Card(heading);
            card.note(explanation);
            card.line("Takes recorded", takesValue, null);
            card.line(artefactName, artefactValue, null);
            card.row(row(takes, record, recordReason));
            card.note(warning);
            card.row(armRow);
            card.row(row(action, reason));
            card.row(progress);
            card.note(report);
            cardPanel = card;
            // Escape does what Cancel does, from anywhere in this card, while
            // there is an arm or a count to cancel. Disabled the rest of the
            // time, so the key is not swallowed when there is nothing to stop.
            card.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "voice.cancel");
            card.getActionMap().put("voice.cancel", new AbstractAction() {
                @Override
                public boolean isEnabled() {
                    return armed || counting();
                }

                @Override
                public void actionPerformed(ActionEvent e) {
                    disarm();
                }
            });
            return card;
        }

        /**
         * Ends an arm or a count-in, before anything has been asked of the
         * microphone, and hands focus back to "Record takes..." if it was in
         * this card. The row that held it has just been hidden, and left to
         * Swing, focus went on to the primary action beside it.
         */
        private void disarm() {
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean focusHere = owner != null && cardPanel != null
                && SwingUtilities.isDescendingFrom(owner, cardPanel);
            armed = false;
            stopCounting();
            applyState();
            if (focusHere) {
                record.requestFocusInWindow();
            }
        }

        /** Whether this half is counting the owner in rather than recording yet. */
        boolean counting() {
            return counting > 0;
        }

        private void stopCounting() {
            counting = 0;
            countIn.stop();
        }

        /**
         * One second of the count-in. At zero the command goes, and not before.
         *
         * <p>This is the only place {@code record} is sent, so there is no path
         * from a click to an open microphone that skips the count.
         */
        private void tick() {
            counting--;
            if (counting > 0) {
                applyState();
                return;
            }
            stopCounting();
            send(Map.of("cmd", "record", "kind", kind,
                "takes", ((Number) takes.getValue()).intValue()), this);
        }

        /** The sentence the owner reads before any microphone is opened. */
        private String warningSentence() {
            int wanted = ((Number) takes.getValue()).intValue();
            return "This opens the microphone and records " + count(wanted, "take") + " of "
                + secondsPerTake + " seconds, one after another with no pause between them. "
                + "Nothing leaves this machine, and the new takes are added to the "
                + count(takesOnDisk(), "take") + " already recorded rather than replacing them.";
        }

        /**
         * The count, said out loud, with the one caveat that makes it honest.
         *
         * <p>With listening on, the sidecar has to get the device back from its
         * own capture thread before the first frame is captured, and that wait
         * is bounded by five seconds rather than instant. Promising the exact
         * moment would be a promise this panel cannot keep.
         */
        private String countdownSentence() {
            return "The microphone opens in " + counting + "…"
                + (listening ? " Listening has to let go of it first, which can take a "
                             + "moment." : "")
                + " Cancel stops it; nothing has been recorded yet.";
        }

        private int takesOnDisk() {
            return "reference".equals(kind) ? referenceTakes : wakeTakes;
        }

        void startProgress(int total, String what) {
            progress.setIndeterminate(false);
            progress.setMinimum(0);
            progress.setMaximum(Math.max(total, 1));
            progress.setValue(0);
            progress.setString(what);
        }

        void take(int number, double level) {
            boolean tooQuiet = level < QUIET;
            if (tooQuiet) {
                quiet++;
            }
            lines.add(String.format(Locale.ROOT, "take %d - %s (%.3f)", number,
                tooQuiet ? "too quiet - move closer" : "good", level));
            // Muted while the takes arrive, whatever their level. The verdict is
            // in the words on the line it belongs to; colouring the whole block
            // amber for one quiet take prints "good" in amber as well.
            report(lines, "text.secondary");
            progress.setValue(Math.min(progress.getValue() + 1, progress.getMaximum()));
            // The first take is the proof that the device really did open.
            progress.setString("recording - " + progress.getValue() + " of "
                + progress.getMaximum() + " done");
        }

        void recorded(int written) {
            lines.add(count(written, "take") + " recorded.");
            if (quiet > 0) {
                lines.add(quiet + " of them came out too quiet to train on. Move closer and "
                    + "record that many again - the quiet ones stay on disk, so delete them "
                    + "from the voice folder if you would rather not train on them.");
            }
            report(lines, quiet > 0 ? "warning" : "text.secondary");
        }

        void progressed(String stage, int done, int total, String detail) {
            progress.setIndeterminate(false);
            progress.setMinimum(0);
            progress.setMaximum(Math.max(total, 1));
            progress.setValue(Math.min(done, Math.max(total, 1)));
            progress.setString(stage + " " + detail + " (" + done + " of " + total + ")");
        }

        /** What enrolment or training produced, as the numbers that decide trust. */
        void trained(SidecarEvent event) {
            List<String> result = new ArrayList<>();
            String token = "text.secondary";
            if ("enrol".equals(command)) {
                result.add(count((int) event.number("takes"), "take")
                    + " averaged into the reference.");
                for (JsonNode row : event.body().path("similarities")) {
                    result.add(String.format(Locale.ROOT, "%s - %.3f", row.path(0).asText(),
                        row.path(1).asDouble()));
                }
                result.add("Those numbers are how closely each take matches the average of "
                    + "them all. One that sits well below the rest was a moment rather than "
                    + "your voice, and the reference is better without it.");
                String warned = event.text("warning");
                if (!warned.isEmpty()) {
                    result.add(warned);
                    token = "warning";
                }
            } else {
                int recognised = (int) event.number("recognised");
                int takesUsed = (int) event.number("takes");
                int negatives = (int) event.number("negatives");
                int falsePositives = (int) event.number("falsePositives");
                result.add("Recognised " + recognised + " of your own " + takesUsed
                    + " takes, and fired on " + falsePositives + " of " + negatives
                    + " clips that were not the wake word.");
                if (falsePositives > 0) {
                    result.add(falsePositives + " false triggers in training is a model that "
                        + "will talk to the room. Record more takes, and more varied ones, "
                        + "then train again.");
                    token = "warning";
                } else if (recognised < takesUsed) {
                    result.add("It missed " + (takesUsed - recognised) + " of your own takes. "
                        + "More takes, said more ways, is what improves that.");
                    token = "warning";
                }
            }
            report(result, token);
        }

        void report(String sentence, String token) {
            report(sentence.isEmpty() ? List.of() : List.of(sentence), token);
        }

        /**
         * Writes this half's report. A failure or a warning is also scrolled
         * into view: it lands under the primary action, and at the default
         * size that is below the fold for the wake word half, where a failed
         * run otherwise said nothing anyone could see.
         */
        void report(List<String> sentences, String token) {
            report.setText(UiTheme.html(sentences));
            UiTheme.recolour(report, token);
            report.setVisible(!sentences.isEmpty());
            if (!sentences.isEmpty() && ("error".equals(token) || "warning".equals(token))) {
                ContentPane.revealWhenLaidOut(report);
            }
        }

        /**
         * Everything this half shows, from the fields the last {@code
         * voice.status} set and from whether a command is running.
         */
        void apply(boolean live, int onDisk, boolean artefact, String why, boolean actionable,
                   String whyNoRecording) {
            if (!live && !counting()) {
                // Consent to open a microphone is given for a moment, not kept.
                // An arm that survived a training run would sit there through it
                // and start recording on a click made much later. A count-in is
                // exempt because it is what made the panel busy in the first
                // place, and cancelling it is the point of the button below.
                armed = false;
            }
            takesValue.setText(count(onDisk, "recording"));
            UiTheme.recolour(takesValue, onDisk > 0 ? "text.primary" : "text.secondary");
            artefactValue.setText(artefact ? artefactWord : "missing");
            UiTheme.recolour(artefactValue, artefact ? "success" : "warning");
            record.setEnabled(live && !armed);
            recordReason.setText(whyNoRecording);
            confirm.setEnabled(live && armed);
            // The one button that stays live while the panel is busy: until the
            // count reaches zero nothing has been asked of the microphone, and
            // taking the way out away would make the count a formality.
            cancel.setEnabled(armed || counting());
            warning.setText(UiTheme.html(counting() ? countdownSentence() : warningSentence()));
            warning.setVisible(armed || counting());
            armRow.setVisible(armed || counting());
            action.setEnabled(live && actionable);
            reason.setText(why);
            progress.setVisible(pendingSection == this);
        }
    }

    /** Repaints every control from what the sidecar last said. */
    private void applyState() {
        boolean idle = pendingId == null && !reference.counting() && !wake.counting();
        boolean live = idle && answered && !unavailable;
        reference.apply(live, referenceTakes, hasReference, enrolReason(),
            VoicePreconditions.canEnrol(referenceTakes, hasSpeakerModel), waiting());
        wake.apply(live, wakeTakes, hasWakeModel, trainReason(),
            VoicePreconditions.canTrain(wakeTakes, featureModels, negatives), waiting());

        listenState.setText(listening ? "on" : "off");
        UiTheme.recolour(listenState, listening ? "success" : "text.secondary");
        listenToggle.setSelected(listening);
        listenToggle.setEnabled(live && hasWakeModel);
        String why = waiting();
        if (why.isEmpty() && !hasWakeModel) {
            why = "there is no wake-word model to listen for";
        }
        listenReason.setText(why);
        listenReport.setVisible(!listenMessage.isEmpty());

        revalidate();
        repaint();
    }

    /** Why nothing in the section can be pressed yet, or {@code ""} if it can. */
    private String waiting() {
        if (unavailable) {
            return "the sidecar that is running has no voice support";
        }
        if (!answered) {
            return "waiting for the sidecar to say what is on disk";
        }
        if (reference.counting() || wake.counting()) {
            return "waiting for the recording about to start";
        }
        if (pendingId != null) {
            // Said beside every dead button rather than only beside the one that
            // is working. The sidecar runs one thing at a time and answers a
            // second command with BUSY; this is that same sentence, in front of
            // the refusal instead of after it.
            return "waiting for the run already going";
        }
        return "";
    }

    private String enrolReason() {
        String waiting = waiting();
        return waiting.isEmpty()
            ? VoicePreconditions.enrolBlocker(referenceTakes, hasSpeakerModel) : waiting;
    }

    private String trainReason() {
        String waiting = waiting();
        if (!waiting.isEmpty()) {
            return waiting;
        }
        String recommendation = VoicePreconditions.WAKE_RECOMMENDED + " takes recommended; "
            + wakeTakes + " recorded";
        if (wakeTakes < VoicePreconditions.WAKE_MINIMUM) {
            return recommendation;
        }
        // Takes are not training's only precondition, any more than they are
        // enrolment's.
        String blocker = VoicePreconditions.trainBlocker(featureModels, negatives);
        if (!blocker.isEmpty()) {
            return blocker;
        }
        if (wakeTakes < VoicePreconditions.WAKE_RECOMMENDED) {
            return recommendation;
        }
        return "";
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

    private static String count(int howMany, String noun) {
        return howMany + " " + noun + (howMany == 1 ? "" : "s");
    }
}
