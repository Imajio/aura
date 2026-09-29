package aura.app.ui;

import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which palette is current, and who hears about it changing.
 *
 * <p>{@link UiTheme} holds the two palettes and the lookup that reads them;
 * this class holds the one piece of state that says which of the two is live,
 * and the listeners a panel registers to repaint itself when that changes.
 * {@link #install(Mode)} is the single place that flips the mode, refreshes
 * every open window's component tree, and then runs the listeners.
 *
 * <p>A listener that throws is logged and skipped rather than allowed to stop
 * the walk, for the same reason {@code SidecarEvents} swallows a subscriber's
 * exception: one panel's bug must not be able to leave every other panel
 * unpainted.
 *
 * <p><b>No look and feel default is set here, and none can carry the
 * theme.</b> {@code SwingUtilities.updateComponentTreeUI} copies a colour from
 * the look and feel's defaults into a component's own field only the first
 * time that component's {@code updateUI()} runs after the default is already
 * right, not on every later {@link #install(Mode)}. Every such key the port
 * brought from {@code JetTheme.install} froze a colour at whichever mode came
 * first, and each was removed. A component that tracks the theme reads {@link
 * UiTheme} itself, when it paints or inside a listener registered with
 * {@link #onChange(Runnable)}, the way {@code AuraWindow} does for its own
 * rail and theme control.
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
     *
     * <p>Returns a handle whose {@code run()} unregisters this exact
     * listener - a plain {@link Runnable} rather than a new public type,
     * since removing a listener is itself just an action with no result. A
     * caller that never means to unregister, which is most of the callers
     * this class had before this handle existed, keeps compiling unchanged:
     * a method call is a valid statement whatever it returns, so the result
     * can simply be dropped. {@link JetControls} is the one that uses it,
     * from {@link java.awt.Component#removeNotify()}.
     */
    public static Runnable onChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * How many listeners are currently registered. Package-private and only
     * for a caller measuring the shape of this list, not for anything the
     * running application needs to know about itself.
     */
    static int listenerCount() {
        return listeners.size();
    }

    /**
     * Makes {@code m} the current mode, refreshes every open window's
     * component tree, and then runs the registered listeners.
     *
     * <p>A listener that throws is logged and does not stop the remaining
     * listeners from running, nor this call from completing.
     *
     * <p>All of it happens on the event dispatch thread. Called from any other
     * thread, this waits until the event thread has done the work, so the mode
     * is switched and every listener has run by the time it returns, wherever
     * it was called from. The tree walk and the listeners set colours, borders
     * and text on live components, and a component touched off that thread
     * while the event thread is changing the same tree fails rarely and
     * somewhere unrelated. It happened here: the window was built on the main
     * thread, and this ran there while the event thread was still adding the
     * sections the walk was meant to reach.
     */
    public static void install(Mode m) {
        if (!SwingUtilities.isEventDispatchThread()) {
            installFromAnotherThread(m);
            return;
        }
        mode = m;
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
     * Runs {@link #install(Mode)} on the event thread and waits for it.
     *
     * <p>An interrupt ends the wait but not the switch, which is already queued
     * and still happens; the interrupt is put back for the caller to see. A
     * listener's exception never gets here, since the loop above logs it, so
     * what arrives wrapped is the tree walk's own and is rethrown as it was.
     */
    private static void installFromAnotherThread(Mode m) {
        try {
            SwingUtilities.invokeAndWait(() -> install(m));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while waiting for the switch to {} on the event thread", m);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("switching the theme failed", cause);
        }
    }
}
