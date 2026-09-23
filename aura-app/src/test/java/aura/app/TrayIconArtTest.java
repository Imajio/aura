package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The mark is the owner's logo rather than something drawn on the fly, so what
 * a test can check is that every state still produces an image at every size
 * the taskbar might ask for, that READY is the plain logo with no badge on
 * it, and that the one state meant to be noticed, WAITING, is the only one
 * that paints an amber badge and paints the largest one.
 */
class TrayIconArtTest {

    private static final int[] TRAY_SIZES = {16, 20, 24, 32};

    @ParameterizedTest
    @EnumSource(TrayIconArt.State.class)
    void everyStateDrawsSomethingAtEverySize(TrayIconArt.State state) {
        for (int size : TRAY_SIZES) {
            BufferedImage image = TrayIconArt.render(state, size);

            assertThat(image.getWidth()).isEqualTo(size);
            assertThat(image.getHeight()).isEqualTo(size);
            assertThat(opaquePixels(image)).as("size %d", size).isGreaterThan(size * 2);
        }
    }

    @Test
    void readyIsExactlyThePlainLogo() {
        for (int size : TRAY_SIZES) {
            BufferedImage ready = TrayIconArt.render(TrayIconArt.State.READY, size);
            BufferedImage plain = TrayIconArt.logo(size);

            assertThat(ready.getWidth()).isEqualTo(plain.getWidth());
            assertThat(ready.getHeight()).isEqualTo(plain.getHeight());
            assertThat(differingPixels(ready, plain)).as("size %d", size).isZero();
        }
    }

    @Test
    void waitingsBadgeCoversMorePixelsThanWorkings() {
        for (int size : TRAY_SIZES) {
            BufferedImage plain = TrayIconArt.logo(size);
            int waitingBadge = differingPixels(
                TrayIconArt.render(TrayIconArt.State.WAITING, size), plain);
            int workingBadge = differingPixels(
                TrayIconArt.render(TrayIconArt.State.WORKING, size), plain);

            assertThat(waitingBadge).as("size %d", size).isGreaterThan(workingBadge);
        }
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

        // The same has to be true of what actually gets painted, not only of
        // the colour a state is assigned to: the pixel at the shared badge
        // centre is amber for WAITING and for no other state.
        int size = 32;
        int centre = (int) Math.round(TrayIconArt.badgeCentre(size));
        int attention = TrayIconArt.ATTENTION.getRGB();
        for (TrayIconArt.State state : TrayIconArt.State.values()) {
            int pixel = TrayIconArt.render(state, size).getRGB(centre, centre);
            if (state == TrayIconArt.State.WAITING) {
                assertThat(pixel).as("badge pixel for WAITING").isEqualTo(attention);
            } else {
                assertThat(pixel).as("badge pixel for %s", state).isNotEqualTo(attention);
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

    /** Counts pixels where two same sized images disagree, badge or no badge. */
    private static int differingPixels(BufferedImage a, BufferedImage b) {
        int count = 0;
        for (int x = 0; x < a.getWidth(); x++) {
            for (int y = 0; y < a.getHeight(); y++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }
}
