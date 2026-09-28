package aura.app.ui;

import java.awt.Dimension;
import java.io.IOException;

/**
 * The size the window should open at next time, and whether {@code config.yaml}
 * still needs to be told it.
 *
 * <p>Two sizes are kept. The normal size is the last one the frame had while it
 * was neither maximised nor minimised: a maximised frame reports the whole work
 * area, and saving that would open the next start at the maximised size with
 * nothing to restore to. The saved size is the one the next start opens at if
 * nothing is written now, so a window nobody resized writes nothing, and the
 * default size can change in code without every config file pinning the old one.
 *
 * <p>A class of its own, like {@link WindowSize}, so the rules can be tested
 * without {@link AuraWindow}, whose static initializer sets the look and feel for
 * the whole JVM. Synchronized because two threads save: the event thread when
 * the window closes, and the shutdown hook when Aura exits with the window open
 * or the session ends. Check, write and record happen under one lock, so the
 * two cannot both write, and neither records a size the other has not written.
 */
final class RememberedSize {

    /** Writes one size somewhere it will be read back at the next start. */
    @FunctionalInterface
    interface Writer {
        void write(int width, int height) throws IOException;
    }

    private Dimension normal;
    private Dimension saved;

    /** @param opening the size the window opened at, which is what the next start opens at if nothing changes */
    RememberedSize(Dimension opening) {
        normal = new Dimension(opening);
        saved = new Dimension(opening);
    }

    /** The frame's new size, which counts only while it is neither maximised nor minimised. */
    synchronized void resized(Dimension size, boolean normalState) {
        if (normalState) {
            normal = new Dimension(size);
        }
    }

    /**
     * Writes the normal size through {@code writer} when it differs from the
     * saved one, and records it as saved once the write has succeeded. A write
     * that throws records nothing, so the next call tries again.
     */
    synchronized void saveIfChanged(Writer writer) throws IOException {
        if (normal.equals(saved)) {
            return;
        }
        writer.write(normal.width, normal.height);
        saved = new Dimension(normal);
    }
}
