package aura.app.ui;

import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * The size the window opens at: the one saved in {@code config.yaml} when there
 * is one, made to fit the screen it opens on.
 *
 * <p>A class of its own rather than a method of {@link AuraWindow}, whose static
 * initializer sets the look and feel for the whole JVM the moment anything
 * touches the class. A test of this arithmetic would otherwise restyle every
 * other test that runs after it.
 */
final class WindowSize {

    private WindowSize() { }

    /**
     * The saved size, or {@code fallback} for a dimension that was never saved
     * (zero), no larger than {@code usable} and no smaller than {@code minimum}.
     *
     * <p>Clamped every time rather than trusted: the file keeps whatever size the
     * window last closed at, and the screen it opens on next can be a smaller one,
     * or the same one with a taller taskbar. A window larger than the work area
     * opens with its title bar or its bottom edge out of reach.
     *
     * <p>The minimum wins over the screen. The frame refuses to be smaller than
     * its minimum whatever it is asked, so a result below it would only be a
     * number that the window then silently ignores.
     *
     * @param usable the screen's bounds less its taskbars, in the same logical
     *               pixels the frame's own size is measured in
     */
    static Dimension opening(int savedWidth, int savedHeight, Dimension fallback,
                             Dimension minimum, Rectangle usable) {
        return new Dimension(
            fit(savedWidth > 0 ? savedWidth : fallback.width, minimum.width, usable.width),
            fit(savedHeight > 0 ? savedHeight : fallback.height, minimum.height, usable.height));
    }

    private static int fit(int wanted, int minimum, int available) {
        return Math.max(minimum, Math.min(wanted, available));
    }
}
