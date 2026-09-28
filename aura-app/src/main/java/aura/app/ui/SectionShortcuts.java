package aura.app.ui;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * Ctrl and a digit select the section at that place in the rail, Ctrl+1 for
 * the first. INTERACTIONS.md asks for accelerators for important actions, and
 * moving between sections is the one thing every visit to the window does.
 *
 * <p>Bound on the root pane with {@link JComponent#WHEN_IN_FOCUSED_WINDOW}, so
 * the keys work wherever focus is: in the phrase field, a spinner, a list or
 * the rail. Only Ctrl with a digit is taken, which no control in the window
 * uses for anything else, so a digit typed into a field still reaches it. No
 * mnemonics: an underlined letter in each section's name would add a second
 * scheme to learn for the same four moves.
 *
 * <p>A class of its own rather than a method of {@link AuraWindow}, whose
 * static initializer sets the look and feel for the whole JVM the moment
 * anything touches the class, so that a test can read the bindings back.
 */
final class SectionShortcuts {

    private SectionShortcuts() { }

    /**
     * Binds Ctrl and {@code place}'s digit on {@code root} to {@code select}.
     * A place past 9 has no digit and gets no key.
     *
     * @param place the section's place in the rail, counted from 1
     */
    static void bind(JComponent root, int place, Runnable select) {
        if (place < 1 || place > 9) {
            return;
        }
        String name = "aura.section." + place;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + place, InputEvent.CTRL_DOWN_MASK), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                select.run();
            }
        });
    }
}
