package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.SidecarEvents.SidecarEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
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
     * of writing to its five cached ones.
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
