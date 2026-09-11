package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
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

    /** Under this many wake takes, training refuses outright - training.py's number. */
    private static final int WAKE_MINIMUM = 5;

    /** And this many is what it takes to be any good. */
    private static final int WAKE_RECOMMENDED = 20;

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

    private final JCheckBox listenToggle = new JCheckBox("Listen for the wake word");
    private final JLabel listenState = UiTheme.status("off", UiTheme.MUTED);
    private final JLabel listenReason = UiTheme.elastic(UiTheme.hint(""));
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

        JScrollPane scroll = new JScrollPane(new ContentPane(column),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(UiTheme.CANVAS);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        setBackground(UiTheme.CANVAS);
        add(scroll, BorderLayout.CENTER);

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
     * on the next press. An error carrying no id at all is the exception: nothing
     * else will arrive to end the command, so it ends it.
     */
    private void failed(SidecarEvent event) {
        String code = event.text("code");
        if ("VOICE_UNAVAILABLE".equals(code)) {
            unavailable = true;
        }
        String answers = event.text("for");
        if (pendingId == null || !(answers.isEmpty() || pendingId.equals(answers))) {
            applyState();
            return;
        }
        Section section = pendingSection;
        String detail = event.text("detail");
        String sentence = code + " - " + (detail.isEmpty() ? "the sidecar refused" : detail);
        if (section != null) {
            section.report(sentence, UiTheme.BAD);
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
                owner.report(sentence, UiTheme.BAD);
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
        listenReport.setForeground(UiTheme.BAD);
    }

    private JComponent listeningCard() {
        Card card = new Card("Listening");
        card.line("Microphone", listenState, null);
        listenToggle.setName("voice.listen.toggle");
        listenToggle.setFont(UiTheme.body());
        listenToggle.setOpaque(false);
        listenToggle.setFocusPainted(false);
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
        private final JButton record = button("Record takes…");
        private final JButton confirm;
        private final JButton cancel = button("Cancel");
        private final JButton action;
        private final JLabel takesValue = UiTheme.status("", UiTheme.MUTED);
        private final JLabel artefactValue = UiTheme.status("", UiTheme.MUTED);
        private final JLabel reason = UiTheme.elastic(UiTheme.hint(""));
        private final JLabel recordReason = UiTheme.elastic(UiTheme.hint(""));
        private final JLabel warning = UiTheme.wrapped("", UiTheme.WARN);
        private final JProgressBar progress = new JProgressBar();
        private final JLabel report = UiTheme.wrapped("");
        private final JPanel armRow;

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

            takes = new JSpinner(new SpinnerNumberModel(defaultTakes, 1, 50, 1));
            // A Swing Timer, so every tick lands on the event dispatch thread
            // like everything else that touches these components.
            countIn = new Timer(1000, e -> tick());
            confirm = button("Open the microphone and record");
            action = button(actionLabel);
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

            Dimension spinnerSize = new Dimension(64, takes.getPreferredSize().height);
            takes.setPreferredSize(spinnerSize);
            takes.setMaximumSize(spinnerSize);
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
                report("", UiTheme.MUTED);
                applyState();
            });
            cancel.addActionListener(e -> {
                // Cancels an arm and a count-in alike. Stopping the count is the
                // last moment at which nothing has been asked of the microphone,
                // so the button that offers it stays live for the whole count.
                armed = false;
                stopCounting();
                applyState();
            });
            confirm.addActionListener(e -> {
                armed = false;
                counting = COUNT_IN;
                countIn.setInitialDelay(tickMillis);
                countIn.setDelay(tickMillis);
                countIn.restart();
                applyState();
            });
            action.addActionListener(e -> {
                report("", UiTheme.MUTED);
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
            return card;
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
            report(lines, UiTheme.MUTED);
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
            report(lines, quiet > 0 ? UiTheme.WARN : UiTheme.MUTED);
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
            Color colour = UiTheme.MUTED;
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
                    colour = UiTheme.WARN;
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
                    colour = UiTheme.WARN;
                } else if (recognised < takesUsed) {
                    result.add("It missed " + (takesUsed - recognised) + " of your own takes. "
                        + "More takes, said more ways, is what improves that.");
                    colour = UiTheme.WARN;
                }
            }
            report(result, colour);
        }

        void report(String sentence, Color colour) {
            report(sentence.isEmpty() ? List.of() : List.of(sentence), colour);
        }

        void report(List<String> sentences, Color colour) {
            report.setText(UiTheme.html(sentences));
            report.setForeground(colour);
            report.setVisible(!sentences.isEmpty());
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
            takesValue.setForeground(onDisk > 0 ? UiTheme.INK : UiTheme.MUTED);
            artefactValue.setText(artefact ? artefactWord : "missing");
            artefactValue.setForeground(artefact ? UiTheme.GOOD : UiTheme.WARN);
            record.setEnabled(live && !armed);
            recordReason.setText(whyNoRecording);
            recordReason.setToolTipText(whyNoRecording.isEmpty() ? null : whyNoRecording);
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
            reason.setToolTipText(why.isEmpty() ? null : why);
            progress.setVisible(pendingSection == this);
        }
    }

    /** Repaints every control from what the sidecar last said. */
    private void applyState() {
        boolean idle = pendingId == null && !reference.counting() && !wake.counting();
        boolean live = idle && answered && !unavailable;
        reference.apply(live, referenceTakes, hasReference, enrolReason(),
            referenceTakes > 0 && hasSpeakerModel, waiting());
        wake.apply(live, wakeTakes, hasWakeModel, trainReason(), canTrain(), waiting());

        listenState.setText(listening ? "on" : "off");
        listenState.setForeground(listening ? UiTheme.GOOD : UiTheme.MUTED);
        listenToggle.setSelected(listening);
        listenToggle.setEnabled(live && hasWakeModel);
        String why = waiting();
        if (why.isEmpty() && !hasWakeModel) {
            why = "there is no wake-word model to listen for";
        }
        listenReason.setText(why);
        listenReason.setToolTipText(why.isEmpty() ? null : why);
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
        if (!waiting.isEmpty()) {
            return waiting;
        }
        if (referenceTakes == 0) {
            return "no takes yet - record some first";
        }
        if (!hasSpeakerModel) {
            // The one precondition that is not a recording: enrolment runs the
            // takes through models/speaker.onnx, which is downloaded by hand.
            return "the speaker model is missing - see models\\speaker.onnx";
        }
        return "";
    }

    private String trainReason() {
        String waiting = waiting();
        if (!waiting.isEmpty()) {
            return waiting;
        }
        String recommendation = WAKE_RECOMMENDED + " takes recommended; " + wakeTakes
            + " recorded";
        if (wakeTakes < WAKE_MINIMUM) {
            return recommendation;
        }
        // Takes are not training's only precondition, any more than they are
        // enrolment's. Without these two the sidecar answers NO_FEATURE_MODELS
        // or refuses for want of anything to train against - both of which the
        // panel can see coming in voice.status.
        if (!featureModels) {
            return "openWakeWord's models are missing - see models\\openwakeword";
        }
        if (negatives == 0) {
            return "nothing to train against - no audio that is not the wake word";
        }
        if (wakeTakes < WAKE_RECOMMENDED) {
            return recommendation;
        }
        return "";
    }

    /** Whether {@code train.wake} would get past its own preconditions. */
    private boolean canTrain() {
        return wakeTakes >= WAKE_MINIMUM && featureModels && negatives > 0;
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

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(UiTheme.body());
        button.setFocusPainted(false);
        return button;
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static String count(int howMany, String noun) {
        return howMany + " " + noun + (howMany == 1 ? "" : "s");
    }
}
