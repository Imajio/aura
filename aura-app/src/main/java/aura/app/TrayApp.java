package aura.app;

import aura.core.Verbosity;
import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tray icon: task entry, what Aura is doing, how much it should say, and the log.
 *
 * <p>Everything here exists because the alternative was worse, not because a tray
 * menu wants filling. The status line is a menu item rather than only a tooltip
 * because a tooltip has to be hunted for with the mouse. The log entry is there
 * because Aura is normally started from a shortcut, where there is no console and
 * a user whose agent misbehaved otherwise has nothing to look at.
 */
public final class TrayApp {

    private static final Logger log = LoggerFactory.getLogger(TrayApp.class);

    private final TrayIcon icon;
    private final int iconSize;
    private final MenuItem statusItem;
    private final Map<Verbosity, CheckboxMenuItem> verbosityItems = new EnumMap<>(Verbosity.class);

    public TrayApp(Consumer<String> onTask, Runnable onStop, Runnable onExit,
                   Consumer<Verbosity> onVerbosity, Verbosity verbosity, Path logDir)
            throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("system tray unavailable");
        }

        PopupMenu menu = new PopupMenu();

        // First line, greyed out: not a command, just the answer to "what is it
        // doing?" without having to hover over the icon and wait for a tooltip.
        statusItem = new MenuItem("starting…");
        statusItem.setEnabled(false);
        menu.add(statusItem);
        menu.addSeparator();

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

        menu.add(newTask);
        menu.add(stop);
        menu.addSeparator();
        menu.add(narrationMenu(onVerbosity, verbosity));
        menu.add(openLogItem(logDir));
        menu.addSeparator();

        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(e -> onExit.run());
        menu.add(exit);

        // Ask the tray how big it wants the icon instead of handing it 16 pixels
        // and letting it stretch: 20 at 125% and 24 at 150% are what a normal
        // Windows desktop asks for.
        iconSize = Math.max(16, SystemTray.getSystemTray().getTrayIconSize().width);
        icon = new TrayIcon(TrayIconArt.render(TrayIconArt.State.READY, iconSize), "Aura", menu);
        icon.setImageAutoSize(false);
        SystemTray.getSystemTray().add(icon);
    }

    /** The talkativeness dial, as checkboxes that behave like radio buttons. */
    private Menu narrationMenu(Consumer<Verbosity> onVerbosity, Verbosity current) {
        Menu narration = new Menu("Narration");
        for (Verbosity level : Verbosity.values()) {
            CheckboxMenuItem item = new CheckboxMenuItem(label(level), level == current);
            item.addItemListener(e -> {
                select(level);
                onVerbosity.accept(level);
            });
            verbosityItems.put(level, item);
            narration.add(item);
        }
        return narration;
    }

    private static String label(Verbosity level) {
        return switch (level) {
            case QUIET -> "Quiet — permissions, errors, results";
            case NORMAL -> "Normal";
            case VERBOSE -> "Verbose — every step";
        };
    }

    /** AWT has no radio group, so exclusivity is kept by hand. */
    private void select(Verbosity chosen) {
        verbosityItems.forEach((level, item) -> item.setState(level == chosen));
    }

    private static MenuItem openLogItem(Path logDir) {
        MenuItem item = new MenuItem("Open log folder");
        item.addActionListener(e -> {
            try {
                Files.createDirectories(logDir);
                Desktop.getDesktop().open(logDir.toFile());
            } catch (IOException | UnsupportedOperationException ex) {
                // Failing to open a folder is not worth a dialog, but a silent
                // no-op would look like a broken menu item.
                log.warn("could not open the log folder {}", logDir, ex);
            }
        });
        return item;
    }

    public void status(String text) {
        icon.setToolTip("Aura — " + text);
        statusItem.setLabel(text);
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

    public void notice(String text) {
        icon.displayMessage("Aura", text, TrayIcon.MessageType.INFO);
    }

    public void remove() {
        SystemTray.getSystemTray().remove(icon);
    }
}
