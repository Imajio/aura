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
     * The handful of Swing defaults left here after Task 2's fix round found
     * that most of what this method used to set does not survive a second
     * {@link #install(Mode)}.
     *
     * <p>Ported from jet-swing-design-system's {@code JetTheme.install} and
     * originally much longer - {@code Label}, {@code Button}, {@code
     * CheckBox}, {@code TextField}, {@code List}, {@code Spinner} and {@code
     * ProgressBar} foregrounds, selection colours and the text field's own
     * background all went through {@link UIManager} the same way the five
     * still here do. Rendering the window through a full switch and a switch
     * back showed why that was wrong: {@code SwingUtilities.updateComponentTreeUI}
     * copies a UIManager colour into a component's own cached field only the
     * first time that component's {@code updateUI()} runs after the key is
     * already correct, not on every later call. {@code AuraWindow}'s own
     * {@code themeToggle} button and its rail's list stayed at whichever mode
     * was current at that one moment, forever, which on this render read as
     * near-white button text on a native, never-themed, always-light button
     * face - unreadable in both themes, on every button in the window, not
     * only the two the owner happened to look at. {@code sectionList} and
     * {@code themeToggle} in {@code AuraWindow} fixed this by setting their
     * own colours explicitly inside a {@link #onChange(Runnable) Theme.onChange}
     * listener instead of leaning on this method; every other panel's
     * buttons, checkboxes, text field, list, spinner and progress bar simply
     * stopped being reached by it, which is the only reset that undoes a
     * once-only miscolouring for a component nothing in this task's scope
     * repaints on a switch.
     *
     * <p>What remains are container backgrounds that no panel in this
     * codebase leaves to the look and feel - every scroll pane, viewport and
     * card already sets its own background explicitly, pinned or live - so
     * removing these two would be a no-op, not a fix, and the two {@code
     * Component.*} keys, which address a FlatLaf-shaped surface this
     * project's plain look and feel does not read; both are kept as the
     * harmless remainder of the original port rather than churned for no
     * behavioural change.
     */
    private static void applyToLookAndFeel() {
        Color primary = UiTheme.surface();
        Color border = UiTheme.line();
        Color accent = UiTheme.accent();

        UIManager.put("Panel.background", primary);
        UIManager.put("Viewport.background", primary);
        UIManager.put("ScrollPane.background", primary);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.focusColor", accent);
    }
}
