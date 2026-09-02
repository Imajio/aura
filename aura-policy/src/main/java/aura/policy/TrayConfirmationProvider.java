package aura.policy;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Modal confirmation dialog — the M1 path, before voice arrives.
 *
 * <p>Stays in the system after M4 too: when the microphone is busy or speech
 * recognition is in the DEGRADED state, the user still has to be asked.
 */
public final class TrayConfirmationProvider implements ConfirmationProvider {

    @Override
    public Decision confirm(ToolRequest request, Duration timeout) {
        if (GraphicsEnvironment.isHeadless()) {
            // With no screen, there is no one to ask. Silent allow is not permitted.
            return Decision.DENY;
        }
        final Decision[] answer = {Decision.DENY};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = new JOptionPane(
                    "The agent wants to run:\n\n" + request.toolName() + "\n"
                        + abbreviate(request.toolInputJson()) + "\n\nin directory "
                        + request.cwd() + "\n\nAllow?",
                    JOptionPane.WARNING_MESSAGE,
                    JOptionPane.YES_NO_OPTION);
                JDialog dialog = pane.createDialog(null, "Aura — confirmation");
                dialog.setAlwaysOnTop(true);

                // The window closes itself. Without this, the timeout would leave a
                // modal dialog sitting on screen, and the agent would wait for a
                // mouse click.
                Timer timer = new Timer((int) timeout.toMillis(), e -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();

                dialog.setVisible(true);
                timer.stop();
                dialog.dispose();

                Object value = pane.getValue();
                answer[0] = (value instanceof Integer i && i == JOptionPane.YES_OPTION)
                    ? Decision.ALLOW : Decision.DENY;
            });
        } catch (InterruptedException e) {
            // Restore the flag before returning. Swallowing it would leave a caller
            // that is itself being cancelled unable to see its own shutdown.
            Thread.currentThread().interrupt();
            return Decision.DENY;
        } catch (Exception e) {
            return Decision.DENY;
        }
        return answer[0];
    }

    private static String abbreviate(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ').trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
