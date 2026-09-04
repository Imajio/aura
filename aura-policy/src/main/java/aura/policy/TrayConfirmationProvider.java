package aura.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Decision confirm(ToolRequest request, Duration timeout) {
        if (GraphicsEnvironment.isHeadless()) {
            // With no screen, there is no one to ask. Silent allow is not permitted.
            return Decision.DENY;
        }
        final Decision[] answer = {Decision.DENY};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = pane(request);
                JDialog dialog = pane.createDialog(null, "Aura — permission");
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

                answer[0] = decisionFrom(pane.getValue());
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

    /** The two answers, spelled as the buttons spell them. */
    static final String YES = "Yes";
    static final String NO = "No";

    /**
     * Builds the question.
     *
     * <p><b>No is the initial value, deliberately.</b> Enter pressed reflexively must
     * not run a command: the permission design says silence is a refusal, and the
     * keyboard has to agree with it.
     */
    static JOptionPane pane(ToolRequest request) {
        Object[] options = {YES, NO};
        return new JOptionPane(message(request), JOptionPane.WARNING_MESSAGE,
            JOptionPane.YES_NO_OPTION, null, options, NO);
    }

    /**
     * What the user reads: which tool, which arguments, in which directory — and
     * nothing else. They are deciding in about two seconds, and a fourth line is a
     * line they will not read.
     */
    static String message(ToolRequest request) {
        return "The agent wants to run:\n\n"
            + request.toolName() + "\n"
            + describeArguments(request.toolInputJson()) + "\n\n"
            + "in " + request.cwd() + "\n\n"
            + "Allow?";
    }

    /**
     * Only an explicit yes is an allowance.
     *
     * <p>A closed window, an escape key and a timeout all arrive here as something
     * that is not {@link #YES}, and every one of them is a refusal.
     */
    static Decision decisionFrom(Object value) {
        return YES.equals(value) ? Decision.ALLOW : Decision.DENY;
    }

    /**
     * Renders tool arguments for a human: one {@code key: value} line per JSON field, in the
     * order the CLI sent them. A user staring at a confirmation dialog reads "command: rm -rf
     * build", not the raw {@code {"command":"rm -rf build"}} it came from.
     *
     * <p>Anything that is not a JSON object — malformed text, an array, a bare scalar — falls
     * back to the flattened raw text: still visible, just not itemised.
     */
    static String describeArguments(String toolInputJson) {
        String raw = toolInputJson == null ? "{}" : toolInputJson;
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            return abbreviate(raw);
        }
        if (!node.isObject() || node.isEmpty()) {
            return abbreviate(raw);
        }

        StringBuilder rendered = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String text = value.isTextual() ? value.asText() : value.toString();
            if (!rendered.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append(field.getKey()).append(": ").append(abbreviate(text));
        }
        return rendered.toString();
    }

    private static String abbreviate(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ').trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
