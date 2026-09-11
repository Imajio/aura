package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * The Voice section, driven the way the sidecar drives it.
 *
 * <p>Asserted on state, never on pixels: which buttons are live, what reason is
 * beside a dead one, what the section says happened. A screenshot test would
 * fail on a font and pass on a panel that had stopped working.
 *
 * <p>Everything runs inside {@link SwingUtilities#invokeAndWait} because that is
 * the contract the panel is written to - {@code AuraWindow} puts every sidecar
 * event on the event dispatch thread before fanning it out, and a test that
 * touched these components from JUnit's own thread would be testing something
 * the application never does.
 *
 * <p>Components are found by name rather than by a getter per control. The panel
 * has thirty-odd of them and an accessor each would be an API nobody calls in
 * production; {@link #find} throws when a name is missing, so a renamed control
 * fails loudly here instead of quietly asserting nothing.
 */
class VoicePanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every button in the section, by name. What "everything is disabled" means. */
    private static final List<String> BUTTONS = List.of(
        "voice.reference.record", "voice.reference.action",
        "voice.wake.record", "voice.wake.action", "voice.listen.toggle");

    /** Each button and the label that has to say why, when it is off. */
    private static final Map<String, String> REASON_FOR = Map.of(
        "voice.reference.record", "voice.reference.recordReason",
        "voice.reference.action", "voice.reference.reason",
        "voice.wake.record", "voice.wake.recordReason",
        "voice.wake.action", "voice.wake.reason",
        "voice.listen.toggle", "voice.listen.reason");

    @Test
    void enrolIsDisabledWithItsReasonUntilTakesExist() {
        // Breaks if Enrol is enabled whenever the sidecar has answered, or if the
        // reason beside it is dropped. Then the owner presses a live button and
        // gets NOT_ENOUGH_TAKES back from the sidecar - an error where the panel
        // already knew the answer.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(0, 0, false, false, true, false);

            assertThat(button(panel.voice, "voice.reference.action").isEnabled()).isFalse();
            assertThat(text(panel.voice, "voice.reference.reason"))
                .contains("no takes yet - record some first");

            panel.status(3, 0, false, false, true, false);

            assertThat(button(panel.voice, "voice.reference.action").isEnabled()).isTrue();
        });
    }

    @Test
    void enrolIsDisabledWhenTheSpeakerModelIsMissingHoweverManyTakesThereAre() {
        // Breaks if takes are treated as enrolment's only precondition. training.py
        // also needs models/speaker.onnx and raises NO_SPEAKER_MODEL without it,
        // and voice.status reports whether it is there - so the panel can say so
        // instead of letting the owner discover it by pressing.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 0, false, false, false, false);

            assertThat(button(panel.voice, "voice.reference.action").isEnabled()).isFalse();
            assertThat(text(panel.voice, "voice.reference.reason")).contains("speaker model");
        });
    }

    @Test
    void trainIsDisabledBelowFiveTakesAndSaysHowFarOffItIs() {
        // Breaks if the panel disables Train at anything short of twenty, or
        // enables it at anything above zero. train_wake_word refuses under five
        // takes and merely recommends twenty, so both mistakes are visible: one
        // blocks a button that would work, the other offers one that cannot.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 4, true, false, true, false);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isFalse();
            assertThat(text(panel.voice, "voice.wake.reason"))
                .contains("20 takes recommended; 4 recorded");

            panel.status(8, 12, true, false, true, false);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isTrue();
            assertThat(text(panel.voice, "voice.wake.reason")).contains("12 recorded");

            panel.status(8, 20, true, false, true, false);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isTrue();
            assertThat(text(panel.voice, "voice.wake.reason")).isEmpty();
        });
    }

    @Test
    void nothingCanBePressedBeforeTheSidecarHasAnswered() {
        // Breaks if the panel starts out live. With no sidecar the send is a
        // no-op, so a pressed button would set the panel running and nothing
        // would ever arrive to set it back - the stuck panel, reached by
        // pressing a button that was never usable.
        onEdt(() -> {
            Panel panel = new Panel();

            for (String name : BUTTONS) {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " before any event").isFalse();
            }
            assertThat(text(panel.voice, "voice.reference.reason")).contains("sidecar");
        });
    }

    @Test
    void everyButtonThatIsOffSaysWhyBesideItself() {
        // The section's central promise, asserted for all five buttons rather
        // than the two that happened to have tests. Blanking either the record
        // rows' reason or the listening switch's used to pass the whole suite,
        // and a reason nobody asserts is one that can quietly become empty.
        Panel panel = panel();
        onEdt(() -> {
            REASON_FOR.forEach((name, reason) -> {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " before any event").isFalse();
                assertThat(text(panel.voice, reason))
                    .as("why " + name + " is off").isNotEmpty();
            });
            panel.status(8, 20, true, true, true, false);
            panel.arm("wake");
        });
        panel.awaitSend();
        onEdt(() -> REASON_FOR.forEach((name, reason) -> {
            assertThat(button(panel.voice, name).isEnabled())
                .as(name + " during a run").isFalse();
            assertThat(text(panel.voice, reason))
                .as("why " + name + " is off during a run").isNotEmpty();
        }));
    }

    @Test
    void trainIsDisabledWhenWhatItTrainsFromIsMissing() {
        // Takes are not training's only precondition. Breaks if the panel offers
        // Train while openWakeWord's models or the negative audio are absent -
        // the owner presses a live button and gets NO_FEATURE_MODELS, which is
        // the failure this whole section is arranged to prevent.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 20, true, false, true, false, false, 143);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isFalse();
            assertThat(text(panel.voice, "voice.wake.reason")).contains("openWakeWord");

            panel.status(8, 20, true, false, true, false, true, 0);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isFalse();
            assertThat(text(panel.voice, "voice.wake.reason")).contains("not the wake word");

            panel.status(8, 20, true, false, true, false, true, 143);

            assertThat(button(panel.voice, "voice.wake.action").isEnabled()).isTrue();
        });
    }

    @Test
    void theMicrophoneIsNotAskedForUntilTheCountReachesZero() {
        // The count-in is the panel's own, run before the command is sent, which
        // is the only place it can honestly run. Breaks if agreeing sends record
        // immediately - the count would then be decoration over a microphone
        // that was already open - or if Cancel stops explaining itself.
        // A minute a tick, so the count cannot reach zero inside the test.
        Panel panel = panel(60_000);
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            panel.arm("wake");

            assertThat(panel.sent).isEmpty();
            assertThat(text(panel.voice, "voice.wake.warning")).contains("opens in 3");
            assertThat(button(panel.voice, "voice.wake.cancel").isEnabled()).isTrue();
            assertThat(button(panel.voice, "voice.wake.record").isEnabled()).isFalse();

            button(panel.voice, "voice.wake.cancel").doClick();

            assertThat(panel.sent).isEmpty();
            assertThat(find(panel.voice, "voice.wake.warning").isVisible()).isFalse();
            assertThat(button(panel.voice, "voice.wake.record").isEnabled()).isTrue();
        });
    }

    @Test
    void theSectionDoesNotSayTheMicrophoneIsOpenUntilATakeProvesIt() {
        // protocol.py emits record.started from the loop thread, before the
        // worker that pauses listening and opens the device has been started -
        // and if that pause fails the device never opens at all. Breaks if the
        // panel treats record.started as proof of an open microphone.
        Panel panel = panel();
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            panel.arm("wake");
        });
        panel.awaitSend();
        onEdt(() -> {
            panel.event("{\"ev\":\"record.started\",\"kind\":\"wake\",\"takes\":2,\"for\":\""
                + panel.lastId() + "\"}");

            assertThat(progress(panel.voice, "voice.wake.progress")).contains("opening")
                .doesNotContain("recording");

            panel.event("{\"ev\":\"record.take\",\"kind\":\"wake\",\"number\":21,"
                + "\"level\":0.08,\"path\":\"voice/wake/wake-021.wav\",\"for\":\""
                + panel.lastId() + "\"}");

            assertThat(progress(panel.voice, "voice.wake.progress")).contains("recording");
        });
    }

    @Test
    void recordingDoesNotStartUntilTheWarningAboutTheMicrophoneIsAccepted() {
        // The one check the product rests on. Breaks if Record sends the command
        // straight away: the microphone would open on a click whose label never
        // said it would, which is the thing this application must never do.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 20, true, true, true, false);

            button(panel.voice, "voice.reference.record").doClick();

            assertThat(panel.sent).isEmpty();
            assertThat(find(panel.voice, "voice.reference.warning").isVisible()).isTrue();
            assertThat(text(panel.voice, "voice.reference.warning")).contains("microphone");
            assertThat(button(panel.voice, "voice.reference.confirm").isVisible()).isTrue();

            button(panel.voice, "voice.reference.cancel").doClick();

            assertThat(panel.sent).isEmpty();
            assertThat(find(panel.voice, "voice.reference.warning").isVisible()).isFalse();
        });
    }

    @Test
    void pressingRecordSendsOneCommandCarryingTheKindAndTheTakesOnTheSpinner() {
        // Breaks if the panel sends the other section's kind, ignores the spinner
        // and sends its default, or sends twice. Twenty takes is ten minutes of
        // the owner's time, so the number on the spinner is not a detail.
        Panel panel = panel();
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            spinner(panel.voice, "voice.wake.spinner").setValue(5);
            panel.arm("wake");
        });
        panel.awaitSend();
        onEdt(() -> {
            assertThat(panel.sent).hasSize(1);
            assertThat(panel.sent.get(0)).containsEntry("cmd", "record")
                .containsEntry("kind", "wake").containsEntry("takes", 5);
        });
    }

    @Test
    void everyButtonIsDisabledWhileACommandRunsAndLiveAgainAfterRecordDone() {
        // Breaks if the panel leaves its buttons live while the sidecar is busy -
        // the second command comes back BUSY, and an error is a worse way to say
        // "not now" than a button that is visibly not offering. Also breaks if
        // record.done is not treated as the end of the run.
        Panel panel = panel();
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            panel.arm("wake");
        });
        panel.awaitSend();
        onEdt(() -> {
            for (String name : BUTTONS) {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " while recording").isFalse();
            }

            panel.event("{\"ev\":\"record.done\",\"kind\":\"wake\",\"takes\":5,\"for\":\""
                + panel.lastId() + "\"}");

            for (String name : BUTTONS) {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " after record.done").isTrue();
            }
        });
    }

    @Test
    void busyRestoresTheButtonsInsteadOfLeavingThePanelStuck() {
        // Named after the failure it prevents. An error is the one answer that
        // ends a command without a done event, and a panel that only listens for
        // record.done or train.done stays disabled for the rest of the session -
        // with no way back except closing the window.
        Panel panel = panel();
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            panel.arm("reference");
        });
        panel.awaitSend();
        onEdt(() -> {
            assertThat(button(panel.voice, "voice.wake.record").isEnabled()).isFalse();

            panel.event("{\"ev\":\"error\",\"code\":\"BUSY\",\"fatal\":false,\"for\":\""
                + panel.lastId() + "\",\"detail\":\"another recording or training run is "
                + "already in progress\"}");

            for (String name : BUTTONS) {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " after BUSY").isTrue();
            }
            assertThat(text(panel.voice, "voice.reference.report")).contains("BUSY");
        });
    }

    @Test
    void aQuietTakeSaysSoAndALoudOneDoesNot() {
        // Breaks if the 0.005 threshold is dropped or its comparison inverted. A
        // take too quiet to hear is worse than a missing one, because nothing
        // downstream refuses it: it is silently trained on.
        Panel panel = panel();
        onEdt(() -> {
            panel.status(8, 20, true, true, true, false);
            panel.arm("wake");
        });
        panel.awaitSend();
        onEdt(() -> {
            panel.event("{\"ev\":\"record.take\",\"kind\":\"wake\",\"number\":21,"
                + "\"level\":0.002,\"path\":\"voice/wake/wake-021.wav\",\"for\":\""
                + panel.lastId() + "\"}");

            assertThat(text(panel.voice, "voice.wake.report"))
                .contains("too quiet - move closer");

            panel.event("{\"ev\":\"record.take\",\"kind\":\"wake\",\"number\":22,"
                + "\"level\":0.081,\"path\":\"voice/wake/wake-022.wav\",\"for\":\""
                + panel.lastId() + "\"}");

            // Named against take 22. The quiet line for take 21 is still in the
            // block, so a bare contains("good") would pass on a panel that gave
            // every take the quiet verdict.
            assertThat(text(panel.voice, "voice.wake.report")).contains("take 22 - good");
        });
    }

    @Test
    void trainDoneRendersBothNumbersAndWarnsWhenClipsThatWereNotTheWakeWordFired() {
        // Breaks if either number is dropped. They are the two that decide whether
        // the model can be trusted, and a false-positive count shown as quietly as
        // a good one is how a model that fires at the room gets left running.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 20, true, true, true, false);

            panel.event("{\"ev\":\"train.done\",\"cmd\":\"train.wake\",\"takes\":20,"
                + "\"negatives\":143,\"recognised\":20,\"falsePositives\":0,"
                + "\"out\":\"voice/wake-word.npz\"}");

            // The phrase, not the digits. "20" and "143" appear on their own in
            // a sentence that never mentions a false positive at all, so a panel
            // that dropped the second number would still pass on the digits.
            assertThat(text(panel.voice, "voice.wake.report"))
                .contains("Recognised 20 of your own 20 takes")
                .contains("fired on 0 of 143")
                .doesNotContainIgnoringCase("false trigger");

            panel.event("{\"ev\":\"train.done\",\"cmd\":\"train.wake\",\"takes\":20,"
                + "\"negatives\":143,\"recognised\":18,\"falsePositives\":14,"
                + "\"out\":\"voice/wake-word.npz\"}");

            assertThat(text(panel.voice, "voice.wake.report"))
                .contains("Recognised 18 of your own 20 takes")
                .contains("fired on 14 of 143")
                .containsIgnoringCase("false trigger");
        });
    }

    @Test
    void theListeningToggleShowsWhatTheSidecarAnsweredAndNotWhatWasClicked() {
        // Breaks the moment somebody lets the click set the state - which a
        // JCheckBox does on its own, so this is not hypothetical. A toggle that
        // believes itself claims the microphone is off while it is still open.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 20, true, true, true, false);

            button(panel.voice, "voice.listen.toggle").doClick();

            assertThat(button(panel.voice, "voice.listen.toggle").isSelected()).isFalse();
            assertThat(text(panel.voice, "voice.listen.state")).isEqualTo("off");
            assertThat(panel.sent).hasSize(1);
            assertThat(panel.sent.get(0)).containsEntry("cmd", "configure")
                .containsEntry("listen", true);

            // Carrying the id the panel sent, because that is what the sidecar's
            // configure branch answers with. Fed without one this test would
            // still pass every assertion below while the panel stayed disabled
            // for the rest of the session, waiting for an answer it had had.
            panel.event("{\"ev\":\"voice.status\",\"referenceTakes\":8,\"wakeTakes\":20,"
                + "\"reference\":true,\"wakeModel\":true,\"speakerModel\":true,"
                + "\"listening\":true,\"for\":\"" + panel.lastId() + "\"}");

            assertThat(button(panel.voice, "voice.listen.toggle").isSelected()).isTrue();
            assertThat(button(panel.voice, "voice.listen.toggle").isEnabled()).isTrue();
            assertThat(text(panel.voice, "voice.listen.state")).isEqualTo("on");
        });
    }

    @Test
    void askingTheSidecarWhatExistsDoesNotDisableAnything() {
        // Breaks if refresh() is written like the other sends and marks a command
        // in flight. The window asks voice.status every time it opens, so the
        // section would come up with every button dead and no reason beside them.
        onEdt(() -> {
            Panel panel = new Panel();
            panel.status(8, 20, true, true, true, false);
            panel.sent.clear();

            panel.voice.refresh();

            assertThat(panel.sent).hasSize(1);
            assertThat(panel.sent.get(0)).containsEntry("cmd", "voice.status");
            for (String name : BUTTONS) {
                assertThat(button(panel.voice, name).isEnabled())
                    .as(name + " after a refresh").isTrue();
            }
        });
    }

    /** The panel, the commands it sent, and the shorthand for driving it. */
    private static final class Panel {

        // Synchronized because the count-in's last tick appends from the event
        // dispatch thread while awaitSend polls from JUnit's.
        private final List<Map<String, Object>> sent =
            java.util.Collections.synchronizedList(new ArrayList<>());
        private final VoicePanel voice;
        private int sentBeforeArming;

        Panel() {
            this(1);
        }

        Panel(int tickMillis) {
            voice = new VoicePanel(sent::add, tickMillis);
        }

        void event(String json) {
            try {
                var node = MAPPER.readTree(json);
                voice.accept(new SidecarEvent(node.path("ev").asText(), node));
            } catch (Exception e) {
                throw new AssertionError("bad test fixture: " + json, e);
            }
        }

        /**
         * One voice.status, the event every other state in the panel hangs off.
         *
         * <p>The feature models and the negative clips default to present, so
         * that a test about takes is about takes. The case where they are
         * missing has a test of its own.
         */
        void status(int referenceTakes, int wakeTakes, boolean reference, boolean wakeModel,
                    boolean speakerModel, boolean listening) {
            status(referenceTakes, wakeTakes, reference, wakeModel, speakerModel, listening,
                true, 143);
        }

        void status(int referenceTakes, int wakeTakes, boolean reference, boolean wakeModel,
                    boolean speakerModel, boolean listening, boolean featureModels,
                    int negatives) {
            event("{\"ev\":\"voice.status\",\"referenceTakes\":" + referenceTakes
                + ",\"wakeTakes\":" + wakeTakes + ",\"reference\":" + reference
                + ",\"wakeModel\":" + wakeModel + ",\"speakerModel\":" + speakerModel
                + ",\"featureModels\":" + featureModels + ",\"negatives\":" + negatives
                + ",\"listening\":" + listening + "}");
        }

        /**
         * The two presses recording takes: the one that asks, and the one that
         * agrees and starts the count-in. Runs on the event dispatch thread.
         *
         * <p>The count is not skipped for tests - it is the path the owner
         * walks, and a suite that jumped over it would be covering a panel
         * nobody has. It is only made fast, at a millisecond a tick.
         */
        void arm(String kind) {
            sentBeforeArming = sent.size();
            button(voice, "voice." + kind + ".record").doClick();
            button(voice, "voice." + kind + ".confirm").doClick();
        }

        /**
         * Waits for the count-in to reach zero and send. Runs on JUnit's thread,
         * never the event dispatch thread: the count ticks <em>on</em> the EDT,
         * so a test still holding it would wait for something that cannot happen.
         * Touches no component - only the list the stub sender appends to.
         */
        void awaitSend() {
            long deadline = System.currentTimeMillis() + 5000;
            while (sent.size() == sentBeforeArming && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            assertThat(sent.size())
                .as("the count-in never reached the sidecar").isGreaterThan(sentBeforeArming);
        }

        /**
         * The correlation id of the last command sent, so a fed event answers the
         * command the panel actually made rather than one this test invented. A
         * literal here would still pass if the panel stopped correlating at all.
         */
        String lastId() {
            assertThat(sent).isNotEmpty();
            return String.valueOf(sent.get(sent.size() - 1).get("id"));
        }
    }

    /** A panel constructed on the event dispatch thread, like every other one. */
    private static Panel panel() {
        return panel(1);
    }

    private static Panel panel(int tickMillis) {
        Panel[] built = new Panel[1];
        onEdt(() -> built[0] = new Panel(tickMillis));
        return built[0];
    }

    /** What the progress bar is saying it is doing. */
    private static String progress(Container root, String name) {
        return ((javax.swing.JProgressBar) find(root, name)).getString();
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

    private static AbstractButton button(Container root, String name) {
        return (AbstractButton) find(root, name);
    }

    private static JSpinner spinner(Container root, String name) {
        return (JSpinner) find(root, name);
    }

    /** A label's text, with the HTML that wraps it taken back off. */
    private static String text(Container root, String name) {
        String raw = ((JLabel) find(root, name)).getText();
        return raw.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replaceAll("\\s+", " ").trim();
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
}
