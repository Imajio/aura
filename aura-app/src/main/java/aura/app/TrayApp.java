package aura.app;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
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

        icon = new TrayIcon(placeholderIcon(), "Aura", menu);
        icon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(icon);
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

    /** The real icon will arrive with the UI; a plain shape is enough for now. */
    private static Image placeholderIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(0x4C, 0x8B, 0xF5));
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        return image;
    }
}
