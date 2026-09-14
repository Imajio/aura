package aura.app.ui;

import java.awt.Color;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which palette is current, and who hears about it changing.
 *
 * <p>{@link UiTheme} holds the two palettes and the lookup that reads them;
 * this class holds the one piece of state that says which of the two is live,
 * and the listeners a panel registers to repaint itself when that changes.
 * {@link #install(Mode)} is the single place that flips the mode, points a
 * small number of {@link UIManager} keys at it, refreshes every open
 * window's component tree, and then runs the listeners.
 *
 * <p>A listener that throws is logged and skipped rather than allowed to stop
 * the walk, for the same reason {@code SidecarEvents} swallows a subscriber's
 * exception: one panel's bug must not be able to leave every other panel
 * unpainted.
 *
 * <p><b>The {@link UIManager} keys {@link #install(Mode)} sets are not a
 * general repaint mechanism, and {@link #applyToLookAndFeel} says why in
 * detail.</b> In short: {@code SwingUtilities.updateComponentTreeUI} only
 * copies a UIManager-supplied colour into a component's own cached field the
 * first time that component's {@code updateUI()} runs after the key is
 * already correct - not on every later {@link #install(Mode)}. A panel that
 * wants to track the theme across repeated switches has to read {@link
 * UiTheme} explicitly inside a listener registered with {@link
 * #onChange(Runnable)}, the way {@code AuraWindow} does for its own rail and
 * theme control; the {@link UIManager} path alone will not do it.
 */
public final class Theme {

    private static final Logger log = LoggerFactory.getLogger(Theme.class);

    public enum Mode { DARK, LIGHT }

    private static volatile Mode mode = Mode.LIGHT;

    // Copy-on-write for the same reason as SidecarEvents' subscriber list:
    // listeners are registered a handful of times, at panel construction, and
    // read on every mode switch, so writes are rare and reads must never
    // throw ConcurrentModificationException.
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Theme() { }

    /** Which palette {@link UiTheme#color(String)} currently reads. */
    public static Mode mode() {
        return mode;
    }

    /**
     * Registers a listener to run after every {@link #install(Mode)}, in the
     * order registered. Meant for a panel that caches a colour rather than
     * reading {@link UiTheme} fresh on every paint.
     */
    public static void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Makes {@code m} the current mode, points the look and feel's own
     * defaults at it, refreshes every open window's component tree, and then
     * runs the registered listeners.
     *
     * <p>A listener that throws is logged and does not stop the remaining
     * listeners from running, nor this call from completing.
     */
    public static void install(Mode m) {
        mode = m;
        applyToLookAndFeel();
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("a theme change listener threw, continuing with the others", e);
            }
        }
    }

    /**
     * The two Swing defaults left here after Task 2's second fix round found
     * that the other three container-background keys do not survive a second
     * {@link #install(Mode)} either.
     *
     * <p>Ported from jet-swing-design-system's {@code JetTheme.install} and
     * originally much longer - {@code Label}, {@code Button}, {@code
     * CheckBox}, {@code TextField}, {@code List}, {@code Spinner} and {@code
     * ProgressBar} foregrounds, selection colours and the text field's own
     * background were the first thirteen keys found to not survive a second
     * {@link #install(Mode)} (fix round 1); {@code Panel}, {@code Viewport}
     * and {@code ScrollPane}'s {@code background} were kept at the time,
     * reasoned to be unreachable because every panel and scroll pane in this
     * codebase already sets its own background. That reasoning checked every
     * panel and missed a scroll pane: {@code TasksPanel.activityCard()}'s
     * inner scroll wraps an empty-when-there-are-no-tasks-yet list in a
     * border with a non-zero top inset (the gap under the "Activity"
     * heading), and a {@code JScrollPane}, if opaque, paints its own
     * background across that inset before the border and the viewport paint
     * over the rest - the one area the list's own explicit, correctly
     * updating background does not reach, since the list otherwise stretches
     * to cover the whole viewport. Rendered with dark installed first: a
     * solid bar the width of the card, gone when light installs first
     * instead - the same once-only propagation this class's own javadoc
     * describes, on a key this method used to set unconditionally.
     *
     * <p>{@code TasksPanel} fixed its one exposed scroll pane by setting its
     * background explicitly, the same way {@code AuraWindow} already did for
     * its own rail. Grepped every {@code JPanel} and {@code JScrollPane}
     * construction in this codebase this time, not just the first hit for
     * each background call: no other one is exposed the same way - the four
     * other "whole column" scroll panes already set their viewport's
     * background explicitly and carry no border gap, {@code VoiceChoicePanel}'s
     * voice list is never empty (ten fixed entries, always more than fit),
     * and every {@code JPanel} either sets its own background or is
     * non-opaque. {@code Panel.background} and {@code Viewport.background}
     * are therefore removed alongside {@code ScrollPane.background}: not
     * because a second exposure was found for either, but because "not
     * currently reachable" is exactly the claim that was wrong about the
     * third, and this method is not the place to keep re-deriving it. What
     * remains are the two {@code Component.*} keys, which address a
     * FlatLaf-shaped surface this project's plain look and feel does not
     * read - kept as the harmless remainder of the original port rather than
     * removed on an unverified guess, the same distinction fix round 1 drew.
     */
    private static void applyToLookAndFeel() {
        Color border = UiTheme.line();
        Color accent = UiTheme.accent();

        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.focusColor", accent);
    }
}
