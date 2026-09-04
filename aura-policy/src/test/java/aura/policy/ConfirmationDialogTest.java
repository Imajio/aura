package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.swing.JOptionPane;
import org.junit.jupiter.api.Test;

/**
 * The permission dialog is the one screen where the design has a cost.
 *
 * <p>The user is interrupted mid-thought and has about two seconds to make a security
 * decision. What it shows, and which button the keyboard lands on, are part of the
 * permission design rather than decoration.
 */
class ConfirmationDialogTest {

    private static final ToolRequest BASH = new ToolRequest(
        "Bash",
        "{\"command\":\"rm -rf build\",\"description\":\"Remove the build folder\"}",
        Path.of("C:", "work", "backend"));

    @Test
    void theQuestionNamesTheToolTheArgumentsAndTheDirectory() {
        String message = TrayConfirmationProvider.message(BASH);

        assertThat(message).contains("Bash");
        assertThat(message).contains("command: rm -rf build");
        assertThat(message).contains("description: Remove the build folder");
        assertThat(message).contains(Path.of("C:", "work", "backend").toString());
    }

    @Test
    void theArgumentsAreReadableNotRawJson() {
        assertThat(TrayConfirmationProvider.message(BASH)).doesNotContain("{\"command\"");
    }

    @Test
    void noIsTheDefaultButton() {
        // Enter pressed reflexively must not run a command. The permission design
        // says silence is refusal; the keyboard has to agree with it.
        JOptionPane pane = TrayConfirmationProvider.pane(BASH);

        assertThat(pane.getInitialValue()).isEqualTo(TrayConfirmationProvider.NO);
        assertThat(pane.getOptions()).containsExactly(
            TrayConfirmationProvider.YES, TrayConfirmationProvider.NO);
    }

    @Test
    void theDialogWarnsRatherThanInforms() {
        assertThat(TrayConfirmationProvider.pane(BASH).getMessageType())
            .isEqualTo(JOptionPane.WARNING_MESSAGE);
    }

    @Test
    void onlyAnExplicitYesIsAnAllowance() {
        assertThat(TrayConfirmationProvider.decisionFrom(TrayConfirmationProvider.YES))
            .isEqualTo(Decision.ALLOW);
        assertThat(TrayConfirmationProvider.decisionFrom(TrayConfirmationProvider.NO))
            .isEqualTo(Decision.DENY);
        // A closed window, an escape key, a timeout: all of them arrive here as
        // something that is not "Yes", and all of them are refusals.
        assertThat(TrayConfirmationProvider.decisionFrom(null)).isEqualTo(Decision.DENY);
        assertThat(TrayConfirmationProvider.decisionFrom(JOptionPane.UNINITIALIZED_VALUE))
            .isEqualTo(Decision.DENY);
    }
}
