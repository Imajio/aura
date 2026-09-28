package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

/**
 * Ctrl+1 to Ctrl+9 select the sections in rail order. The bindings are read
 * back from the maps and their actions run by hand: the window they are bound
 * to in production cannot be built in a test, because AuraWindow's static
 * initializer sets the look and feel for the whole JVM, and whether a real key
 * reaches them wherever focus is is a question for the probe that drives the
 * real window.
 */
class SectionShortcutsTest {

    /**
     * Breaks if a section's key is bound to the wrong section, or on a map
     * that only answers while the root itself or one of its children holds
     * focus: WHEN_IN_FOCUSED_WINDOW is what makes it work from the phrase field,
     * a spinner or the rail alike.
     */
    @Test
    void ctrlAndASectionsPlaceInTheRailSelectsItFromAnywhereInTheWindow() {
        JPanel root = new JPanel();
        List<Integer> selected = new ArrayList<>();
        for (int place = 1; place <= 4; place++) {
            int index = place - 1;
            SectionShortcuts.bind(root, place, () -> selected.add(index));
        }

        InputMap inWindow = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        for (int place = 1; place <= 4; place++) {
            Object name = inWindow.get(KeyStroke.getKeyStroke(KeyEvent.VK_0 + place, InputEvent.CTRL_DOWN_MASK));
            assertThat(name).as("Ctrl+" + place).isNotNull();
            Action action = root.getActionMap().get(name);
            action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, null));
        }

        assertThat(selected).containsExactly(0, 1, 2, 3);
        assertThat(root.getInputMap(JComponent.WHEN_FOCUSED).size()).isZero();
        assertThat(root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).size()).isZero();
    }

    /**
     * Breaks if a digit alone is taken, which would swallow it in the phrase
     * field and the spinners, or if a section past the ninth claims a key that
     * is not a digit.
     */
    @Test
    void onlyCtrlWithADigitIsTakenAndOnlyForTheFirstNineSections() {
        JPanel root = new JPanel();
        for (int place = 1; place <= 10; place++) {
            SectionShortcuts.bind(root, place, () -> { });
        }

        KeyStroke[] ctrlAndADigit = new KeyStroke[9];
        for (int digit = 1; digit <= 9; digit++) {
            ctrlAndADigit[digit - 1] = KeyStroke.getKeyStroke(KeyEvent.VK_0 + digit, InputEvent.CTRL_DOWN_MASK);
        }
        assertThat(root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).allKeys())
            .containsExactlyInAnyOrder(ctrlAndADigit);
    }
}
