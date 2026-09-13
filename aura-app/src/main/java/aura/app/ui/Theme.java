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
 * {@link #install(Mode)} is the single place that flips the mode, refreshes
 * the {@link UIManager} keys the already-installed look and feel reads its
 * defaults from, and then runs the listeners.
 *
 * <p>A listener that throws is logged and skipped rather than allowed to stop
 * the walk, for the same reason {@code SidecarEvents} swallows a subscriber's
 * exception: one panel's bug must not be able to leave every other panel
 * unpainted.
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
     * The subset of Swing's default colours a plain, unstyled component
     * reads, pointed at the current palette. Ported from
     * jet-swing-design-system's {@code JetTheme.install}, trimmed to the
     * component kinds this application actually builds; Aura installs no
     * look and feel of its own, so this only overrides keys on whichever one
     * the platform already installed.
     */
    private static void applyToLookAndFeel() {
        Color primary = UiTheme.surface();
        Color input = UiTheme.color("surface.input");
        Color selection = UiTheme.color("surface.selection");
        Color text = UiTheme.ink();
        Color border = UiTheme.line();
        Color accent = UiTheme.accent();

        UIManager.put("Panel.background", primary);
        UIManager.put("Viewport.background", primary);
        UIManager.put("ScrollPane.background", primary);
        UIManager.put("Label.foreground", text);
        UIManager.put("Button.foreground", text);
        UIManager.put("CheckBox.foreground", text);
        UIManager.put("TextField.foreground", text);
        UIManager.put("TextField.background", input);
        UIManager.put("TextField.caretForeground", accent);
        UIManager.put("List.background", primary);
        UIManager.put("List.foreground", text);
        UIManager.put("List.selectionBackground", selection);
        UIManager.put("List.selectionForeground", text);
        UIManager.put("ProgressBar.background", input);
        UIManager.put("ProgressBar.foreground", accent);
        UIManager.put("Spinner.background", input);
        UIManager.put("Spinner.foreground", text);
        UIManager.put("Component.borderColor", border);
        UIManager.put("Component.focusColor", accent);
    }
}
