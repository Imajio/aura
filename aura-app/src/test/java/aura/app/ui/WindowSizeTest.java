package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * The size the window opens at. The work area below is this machine's: a 1440x900
 * logical screen with a 48 px taskbar, where Windows lets a frame grow to 1455x915
 * with its invisible borders, which is a size a closing window can save.
 */
class WindowSizeTest {

    private static final Dimension DEFAULT = new Dimension(900, 640);
    private static final Dimension MINIMUM = new Dimension(720, 480);
    private static final Rectangle WORK_AREA = new Rectangle(0, 0, 1440, 852);

    @Test
    void aWindowNeverClosedOpensAtTheDefaultSize() {
        assertThat(WindowSize.opening(0, 0, DEFAULT, MINIMUM, WORK_AREA)).isEqualTo(DEFAULT);
    }

    @Test
    void aSavedSizeThatFitsIsTheSizeItOpensAt() {
        assertThat(WindowSize.opening(1000, 700, DEFAULT, MINIMUM, WORK_AREA))
            .isEqualTo(new Dimension(1000, 700));
    }

    /** Breaks if the saved size is trusted, and a window opens with its edges out of reach. */
    @Test
    void aSavedSizeLargerThanTheWorkAreaIsBroughtDownToIt() {
        assertThat(WindowSize.opening(1455, 915, DEFAULT, MINIMUM, WORK_AREA))
            .isEqualTo(new Dimension(1440, 852));
        assertThat(WindowSize.opening(3000, 700, DEFAULT, MINIMUM, WORK_AREA))
            .isEqualTo(new Dimension(1440, 700));
    }

    @Test
    void aSavedSizeBelowTheMinimumOpensAtTheMinimum() {
        assertThat(WindowSize.opening(300, 200, DEFAULT, MINIMUM, WORK_AREA)).isEqualTo(MINIMUM);
    }

    /** The frame refuses to be smaller than its minimum, so the clamp does not ask it to be. */
    @Test
    void theMinimumWinsOverAWorkAreaSmallerThanIt() {
        assertThat(WindowSize.opening(1000, 700, DEFAULT, MINIMUM, new Rectangle(0, 0, 640, 400)))
            .isEqualTo(MINIMUM);
    }

    /** A hand-edited file may carry one of the two keys; the other falls back alone. */
    @Test
    void aDimensionNeverSavedFallsBackOnItsOwn() {
        assertThat(WindowSize.opening(1000, 0, DEFAULT, MINIMUM, WORK_AREA))
            .isEqualTo(new Dimension(1000, 640));
    }
}
