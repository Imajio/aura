package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * StatusPanel's own theme-tracking, the one piece of its token conversion the
 * render harness cannot prove: {@code Shot} always builds the whole window
 * through {@code AuraWindow}, which calls {@code Theme.install} right after
 * constructing every section, so a render looks identical whether or not a
 * listener ran immediately. This test builds the panel directly instead, the
 * way {@code VoiceChoicePanelTest} pins the same rule for {@code
 * VoiceChoicePanel} after fa96b77 found it missing there.
 */
class StatusPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pins the property the measured leak was: rebuild() runs on every
     * voice.status event for as long as the window stays open, so a listener
     * nothing ever released grew without bound over the life of the process
     * - +150 over thirty events, measured before this fix, 5 per event with
     * a floor of 1 because logCard's button is unconditional. Breaks if
     * StatusPanel goes back to building a fresh JButton per rebuild instead
     * of writing to its cached ones.
     */
    @Test
    void rebuildingManyTimesDoesNotAccumulateThemeListenersWithoutBound(@TempDir Path tmp) {
        StatusPanel[] panel = new StatusPanel[1];
        onEdt(() -> panel[0] = new StatusPanel(
            command -> { }, tmp, section -> false, section -> { }));
        int afterConstruction = Theme.listenerCount();

        String worstCase = "{\"ev\":\"voice.status\",\"speakerModel\":false,\"reference\":false,"
            + "\"wakeModel\":false,\"referenceTakes\":0,\"wakeTakes\":0,\"listening\":false}";
        for (int i = 0; i < 30; i++) {
            onEdt(() -> panel[0].accept(event(worstCase)));
        }

        assertThat(Theme.listenerCount() - afterConstruction).isZero();
    }

    /**
     * AuraWindow builds this panel before any section exists, so the "Set this
     * up" buttons were decided against a window with no Voice setup in it, and
     * only a later rebuild corrected them. With no sidecar event to rebuild the
     * cards, they stayed dead, with a tooltip saying the section was not in this
     * build. Breaks if the panel stops answering sectionsChanged, the call
     * AuraWindow makes after each addTab, without waiting for a rebuild.
     */
    @Test
    void setupButtonsFollowASectionAddedAfterTheLastRebuild(@TempDir Path tmp) {
        AtomicBoolean added = new AtomicBoolean(false);
        StatusPanel[] panel = new StatusPanel[1];
        onEdt(() -> panel[0] = new StatusPanel(
            command -> { }, tmp, section -> added.get(), section -> { }));
        onEdt(() -> {
            panel[0].accept(event("{\"ev\":\"ready\",\"devices\":{},\"models\":{}}"));
            AbstractButton setup = (AbstractButton) find(panel[0], "status.listening.setup");
            assertThat(setup.isEnabled()).isFalse();
            assertThat(setup.getToolTipText()).contains("not in this build");

            added.set(true);
            panel[0].sectionsChanged();

            assertThat(setup.isEnabled()).isTrue();
            assertThat(setup.getToolTipText()).isNull();
        });
    }

    /**
     * With no sidecar the Listening card said "it has not been switched on" and
     * sent people to a switch that cannot move until the sidecar answers. Breaks
     * if the card goes back to switch advice, or to a button, before a sidecar
     * has said anything, or if the Sidecar card stops pointing at the log.
     */
    @Test
    void listeningNamesTheMissingSidecarAndOffersNoSwitchBeforeAnyEvent(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            assertThat(cardTexts(panel, "Listening")).contains("the sidecar is not running")
                .noneMatch(text -> text.contains("switched on"))
                .doesNotContain("Set this up");
            assertThat(String.join(" ", cardTexts(panel, "Sidecar"))).contains("the log");
        });
    }

    /** The same for a sidecar built without voice support: it has no switch to offer. */
    @Test
    void listeningNamesAMissingVoiceBuildAndOffersNoSwitch(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            panel.accept(event("{\"ev\":\"ready\",\"devices\":{},\"models\":{}}"));
            panel.accept(event("{\"ev\":\"error\",\"code\":\"VOICE_UNAVAILABLE\"}"));

            assertThat(cardTexts(panel, "Listening")).contains("this sidecar has no voice support")
                .doesNotContain("Set this up");
        });
    }

    /**
     * Status said "8 recordings so far - not enough to train on." while Voice
     * setup had Enrol and Train live beside the same takes. Breaks if the Voice
     * card goes back to a rule of its own instead of the preconditions Voice
     * setup applies.
     */
    @Test
    void takesThatAreEnoughAreCalledEnoughAndPointAtTheStep(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            panel.accept(event(voiceStatus(8, 20, true, true, 100)));

            assertThat(cardTexts(panel, "Voice"))
                .contains("8 recordings so far - enrol them in Voice setup.",
                    "20 recordings so far - train them in Voice setup.")
                .noneMatch(text -> text.contains("not enough"));
        });
    }

    /**
     * Six wake takes clear training's minimum of five, and three reference takes
     * are plenty for enrolment: what is missing is two downloads. Breaks if the
     * card names the takes as the obstacle when Voice setup names the models.
     */
    @Test
    void takesBlockedByAMissingModelNameTheModel(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            panel.accept(event(voiceStatus(3, 6, false, false, 0)));

            assertThat(String.join(" | ", cardTexts(panel, "Voice")))
                .contains("3 recordings so far, but the speaker model is missing")
                .contains("6 recordings so far, but openWakeWord's models are missing")
                .doesNotContain("not enough");
        });
    }

    /** Below training's minimum, the take count is the obstacle, and the card says so. */
    @Test
    void tooFewWakeTakesSayHowManyTrainingNeeds(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            panel.accept(event(voiceStatus(0, 3, true, true, 100)));

            assertThat(cardTexts(panel, "Voice"))
                .contains("3 recordings so far - training needs at least 5.");
        });
    }

    /**
     * The speaker model is a download, and its "Set this up" opened Voice setup,
     * where nothing produces it and, with no takes yet, nothing even named it.
     * Breaks if the row offers that button again, or stops saying where the
     * download is described.
     */
    @Test
    void aMissingSpeakerModelSaysWhereToGetItInsteadOfOfferingVoiceSetup(@TempDir Path tmp) {
        StatusPanel panel = panel(tmp);
        onEdt(() -> {
            panel.accept(event(voiceStatus(0, 0, false, false, 0)));

            List<String> voice = cardTexts(panel, "Voice");
            assertThat(voice).contains("A download rather than a recording: "
                + "sidecar\\README.md has the command.");
            assertThat(voice).filteredOn("Set this up"::equals).hasSize(2);
        });
    }

    private static String voiceStatus(int referenceTakes, int wakeTakes, boolean speakerModel,
                                      boolean featureModels, int negatives) {
        return "{\"ev\":\"voice.status\",\"reference\":false,\"wakeModel\":false,"
            + "\"speakerModel\":" + speakerModel + ",\"featureModels\":" + featureModels
            + ",\"referenceTakes\":" + referenceTakes + ",\"wakeTakes\":" + wakeTakes
            + ",\"negatives\":" + negatives + ",\"listening\":false}";
    }

    private static StatusPanel panel(Path logDir) {
        StatusPanel[] panel = new StatusPanel[1];
        onEdt(() -> panel[0] = new StatusPanel(
            command -> { }, logDir, section -> true, section -> { }));
        return panel[0];
    }

    /** The plain text of every label and button in the card with this heading. */
    private static List<String> cardTexts(Container root, String heading) {
        for (Component child : root.getComponents()) {
            if (child instanceof Card card && card.getComponent(0) instanceof JLabel title
                    && heading.equals(title.getText())) {
                List<String> texts = new ArrayList<>();
                collectTexts(card, texts);
                return texts;
            }
            if (child instanceof Container inner) {
                List<String> found = cardTexts(inner, heading);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectTexts(Container root, List<String> texts) {
        for (Component child : root.getComponents()) {
            String raw = child instanceof JLabel label ? label.getText()
                : child instanceof AbstractButton button ? button.getText() : null;
            if (raw != null && !raw.isEmpty()) {
                texts.add(raw.replaceAll("<[^>]*>", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replaceAll("\\s+", " ").trim());
            }
            if (child instanceof Container inner) {
                collectTexts(inner, texts);
            }
        }
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container inner) {
                Component found = find(inner, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static SidecarEvent event(String json) {
        try {
            var node = MAPPER.readTree(json);
            return new SidecarEvent(node.path("ev").asText(), node);
        } catch (Exception e) {
            throw new AssertionError("bad test fixture: " + json, e);
        }
    }

    /**
     * Breaks if the panel goes back to only registering Theme.onChange without
     * calling it once immediately - built while dark is already the active
     * mode, exactly the order AuraWindow's own constructor uses: StatusPanel
     * first, Theme.install last.
     */
    @Test
    void backgroundMatchesTheModeAlreadyActiveWhenThePanelIsBuilt(@TempDir Path tmp) {
        Theme.Mode before = Theme.mode();
        try {
            Theme.install(Theme.Mode.DARK);
            StatusPanel[] built = new StatusPanel[1];
            onEdt(() -> built[0] = new StatusPanel(
                command -> { }, tmp, section -> false, section -> { }));
            onEdt(() -> assertThat(built[0].getBackground()).isEqualTo(UiTheme.color("surface.app")));
        } finally {
            Theme.install(before);
        }
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
