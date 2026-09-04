package aura.app;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The tray icon: typed task entry, stop, exit.
 *
 * <p>In M1 this is the only way to submit a task. In M2 voice will connect to
 * the same dispatcher, and the tray will remain the fallback input.
 */
public final class TrayApp {

    private final TrayIcon icon;
    private final int iconSize;

    public TrayApp(Consumer<String> onTask, Runnable onStop, Runnable onExit) throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("system tray unavailable");
        }

        PopupMenu menu = new PopupMenu();

        MenuItem newTask = new MenuItem("New task…");
        newTask.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            String phrase = JOptionPane.showInputDialog(null,
                "What should I do? Name the project in the first phrase.",
                "Aura — new task", JOptionPane.QUESTION_MESSAGE);
            if (phrase != null && !phrase.isBlank()) {
                onTask.accept(phrase.trim());
            }
        }));

        MenuItem stop = new MenuItem("Stop agent");
        stop.addActionListener(e -> onStop.run());

        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(e -> onExit.run());

        menu.add(newTask);
        menu.add(stop);
        menu.addSeparator();
        menu.add(exit);

        // Ask the tray how big it wants the icon instead of handing it 16 pixels
        // and letting it stretch: 20 at 125% and 24 at 150% are what a normal
        // Windows desktop asks for, and a stretched 16 is what makes a tray icon
        // look like a smudge.
        iconSize = Math.max(16, SystemTray.getSystemTray().getTrayIconSize().width);
        icon = new TrayIcon(TrayIconArt.render(TrayIconArt.State.READY, iconSize), "Aura", menu);
        icon.setImageAutoSize(false);
        SystemTray.getSystemTray().add(icon);
    }

    /**
     * Shows what Aura is doing.
     *
     * <p>A tray icon that never changes is decoration. This one carries the single
     * status worth reading at a glance — whether anything is expected of the user.
     */
    public void state(TrayIconArt.State state) {
        icon.setImage(TrayIconArt.render(state, iconSize));
    }

    public void status(String text) {
        icon.setToolTip("Aura — " + text);
    }

    public void notice(String text) {
        icon.displayMessage("Aura", text, TrayIcon.MessageType.INFO);
    }

    public void remove() {
        SystemTray.getSystemTray().remove(icon);
    }
}
