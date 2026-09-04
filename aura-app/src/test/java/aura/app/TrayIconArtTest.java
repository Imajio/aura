package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The mark is drawn rather than shipped as a bitmap, so what a test can check is
 * that it is drawn at all, at every size the taskbar might ask for, and that the
 * five states are actually distinguishable from one another.
 */
class TrayIconArtTest {

    @ParameterizedTest
    @EnumSource(TrayIconArt.State.class)
    void everyStateDrawsSomethingAtTraySize(TrayIconArt.State state) {
        BufferedImage image = TrayIconArt.render(state, 16);

        assertThat(image.getWidth()).isEqualTo(16);
        assertThat(opaquePixels(image)).isGreaterThan(8);
    }

    @ParameterizedTest
    @EnumSource(TrayIconArt.State.class)
    void theMarkSurvivesTheSizesWindowsActuallyAsks(TrayIconArt.State state) {
        // 16 at 100%, 20 at 125%, 24 at 150%, 32 at 200%. Drawing at 16 and
        // letting AWT stretch is what makes a tray icon look like a smudge.
        for (int size : new int[] {16, 20, 24, 32, 64}) {
            assertThat(opaquePixels(TrayIconArt.render(state, size)))
                .as("size %d", size)
                .isGreaterThan(size / 2);
        }
    }

    @Test
    void theStatesAreTellableApart() {
        Set<String> fingerprints = new HashSet<>();
        for (TrayIconArt.State state : TrayIconArt.State.values()) {
            fingerprints.add(fingerprint(TrayIconArt.render(state, 32)));
        }

        assertThat(fingerprints).hasSize(TrayIconArt.State.values().length);
    }

    @Test
    void waitingIsTheOnlyAmberState() {
        // Amber is reserved for "you are being waited on". If it leaked into an
        // ordinary state it would stop meaning anything, which is the entire
        // reason the design assigns it one job.
        assertThat(TrayIconArt.State.WAITING.colour()).isEqualTo(TrayIconArt.ATTENTION);
        for (TrayIconArt.State state : TrayIconArt.State.values()) {
            if (state != TrayIconArt.State.WAITING) {
                assertThat(state.colour()).isNotEqualTo(TrayIconArt.ATTENTION);
            }
        }
    }

    @Test
    void aQuestionIsNotAFailure() {
        // Red is for something that broke, amber for something being asked.
        assertThat(TrayIconArt.State.ERROR.colour()).isEqualTo(TrayIconArt.ERROR);
        assertThat(TrayIconArt.State.WAITING.colour()).isNotEqualTo(TrayIconArt.ERROR);
    }

    @Test
    void anImpossibleSizeIsRefusedRatherThanDrawnEmpty() {
        assertThatThrownBy(() -> TrayIconArt.render(TrayIconArt.State.READY, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePermissionQuestionIsTheOneThingThatDemandsAttention() {
        assertThat(TrayIconArt.stateFor(aura.core.EventKind.PERMISSION_REQUEST))
            .isEqualTo(TrayIconArt.State.WAITING);
    }

    @Test
    void workLooksLikeWorkAndFinishingLooksLikeRest() {
        assertThat(TrayIconArt.stateFor(aura.core.EventKind.TOOL_START))
            .isEqualTo(TrayIconArt.State.WORKING);
        assertThat(TrayIconArt.stateFor(aura.core.EventKind.DONE))
            .isEqualTo(TrayIconArt.State.READY);
    }

    @Test
    void anUnknownKindLeavesTheIconAloneRatherThanGuessing() {
        // OTHER is what an unrecognised line becomes. Showing it as work, or as an
        // error, would be inventing a status out of not knowing one.
        assertThat(TrayIconArt.stateFor(aura.core.EventKind.OTHER)).isNull();
    }

    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) > 0x40) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Cheap shape-and-colour signature: enough to tell two marks apart. */
    private static String fingerprint(BufferedImage image) {
        StringBuilder out = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int argb = image.getRGB(x, y);
                out.append((argb >>> 24) > 0x40 ? Integer.toHexString(argb & 0xFFFFFF) : ".");
            }
        }
        return out.toString();
    }
}
