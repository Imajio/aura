package aura.app.ui;

import aura.app.AuraConfig;
import aura.app.SidecarEvents.SidecarEvent;
import aura.core.AgentEvent;
import aura.core.ProjectRegistry;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The desktop window: the application proper, as opposed to the tray icon.
 *
 * <p>The tray stays what it has always been - somewhere to glance at, and a place
 * to type a task from. It cannot answer the questions a person actually has when
 * something is not working: which models loaded, whether the wake word was ever
 * trained, why nothing is listening. Those need room, and room is a window.
 *
 * <p>Sections live in a list down the left rather than in tabs across the top.
 * Tabs would do for two short words; there will be four sections and their names
 * are phrases - "Voice setup", not "Voice" - which a tab strip either truncates
 * or scrolls.
 *
 * <h2>Threads</h2>
 *
 * <p>Sidecar events arrive on the reader thread of another process. Swing has
 * exactly one thread that may touch a realised component, and touching one from
 * anywhere else fails rarely, unreproducibly and usually somewhere unrelated -
 * the worst way to learn about a bug. Every method here that touches a component
 * therefore either runs on the event dispatch thread already or puts itself on
 * it, and every subscriber registered through {@link #subscribe} is called on
 * that thread as a consequence. Sections do not need to hop again.
 *
 * <p>The constructor is the one exception, by necessity rather than oversight:
 * it builds the frame and its panels on whichever thread calls it, the
 * traditional place for a Swing application's first frame - before the frame is
 * realised or shown, which is what {@link #show} still fences. {@code Main}
 * calls it from the main thread, not the EDT.
 *
 * <p>The two methods that touch no component - {@link #subscribe} and {@link
 * #hasSection} - are safe from any thread instead, because they are held in
 * concurrent collections. Hopping them to the EDT would be the other answer, but
 * it would make {@code hasSection} unable to return anything.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Closing the window disposes it and leaves the process running: Aura lives in
 * the tray, and closing a window is not a request to quit. {@link #show} is
 * idempotent - there is one frame for the lifetime of this object, so a second
 * call raises the one that exists instead of opening another.
 *
 * <p>The size the window is closed at is written to {@code config.yaml} and is
 * the size the next start opens at. The section and the position are not: a
 * start opens on Status, which answers "what is missing?", in the middle of the
 * screen.
 *
 * <h2>Theme</h2>
 *
 * <p>The rail's scroll pane and column, the foot under it and the body are the
 * pieces of chrome this class owns directly, and none of them may cache a {@link
 * java.awt.Color} the way a constructor-time field would: {@link #refreshChrome}
 * reads {@link UiTheme}'s accessors fresh and is the only place they touch a
 * colour, called from every {@link Theme#onChange}. A value read once at
 * construction and kept is exactly the defect this milestone is most likely to
 * ship, because it looks correct until somebody switches. The section list itself
 * is a {@link JetControls#list} with a {@link JetControls.RowRenderer}, and the
 * theme control in the foot a {@link JetControls#button}; both keep their own
 * colours.
 *
 * <p>{@link Theme#install} is called from here exactly once, as the constructor's
 * last statement rather than its first: it calls {@code updateComponentTreeUI},
 * which only reaches components already attached to a window, and the panels are
 * not attached until this constructor has built the frame around them. The rail
 * once showed the system's own blue selection bar on a window nobody had switched,
 * because install ran before the list was attached.
 */
public final class AuraWindow implements Consumer<SidecarEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuraWindow.class);

    /** The section the Status panel sends people to when voice setup is missing. */
    public static final String VOICE_SECTION = "Voice setup";

    /**
     * The rail's width and the narrowest the window may be.
     *
     * <p>Named rather than written into the constructor because they set the
     * width a card has to work in, and something has to check that a wrapped
     * sentence still fits it. {@code UiThemeTest} does that arithmetic against
     * these; widening the rail without widening the window fails there instead
     * of cutting a sentence off on screen.
     */
    static final int RAIL_WIDTH = 200;
    static final int MINIMUM_WIDTH = 720;
    static final int MINIMUM_HEIGHT = 480;

    /**
     * The rail's surface, a step off the canvas the sections sit on, as the
     * design system's own AppShell gives its navigation. On the canvas the rail
     * read as part of the page, set apart only by its divider, which in light
     * is the faintest mark in the window at 1.19:1.
     */
    private static final String RAIL_SURFACE = "surface.secondary";

    /** The size the window opens at until it has been closed at another one. */
    private static final int DEFAULT_WIDTH = 900;
    private static final int DEFAULT_HEIGHT = 640;

    static {
        // Once, before the first component exists: a look and feel set after a
        // component is built leaves that component styled the old way. Failing
        // to set it is not worth refusing to open a window over - the fallback
        // is ugly, not broken.
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("could not set the system look and feel, using the default", e);
        }
    }

    private final JFrame frame = new JFrame("Aura");
    private final DefaultListModel<String> sectionNames = new DefaultListModel<>();
    private final JList<String> sectionList = new JList<>(sectionNames);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    // Concurrent, not plain. Both are public API: a later section could subscribe
    // or ask hasSection from a thread that is not the EDT, and the reader here is
    // accept(), iterating listeners on the EDT while that happened. Same choice
    // SidecarEvents made for the same reason - subscriptions are a handful at
    // startup, reads are on every sidecar line, and iteration must never throw
    // ConcurrentModificationException. The map is unordered because nothing reads
    // it in order: the rail's order lives in sectionNames.
    private final Map<String, JComponent> sections = new ConcurrentHashMap<>();
    private final List<Consumer<SidecarEvent>> listeners = new CopyOnWriteArrayList<>();
    private final StatusPanel status;
    private final VoicePanel voice;
    private final VoiceChoicePanel voiceChoice;
    private final TasksPanel tasks;
    // Assigned in the constructor rather than here: rail needs sectionList already
    // configured, and themeToggle's label depends on Theme.mode(), which the
    // constructor only settles once the configured theme has been installed.
    private final JScrollPane rail;
    private final JButton themeToggle = JetControls.button("");
    private final JPanel railColumn = new JPanel(new BorderLayout());
    // Holds themeToggle below the section list, padded so the button lines up
    // with the list's rows and carrying the rail's separator line past it.
    private final JPanel railFoot = new JPanel(new BorderLayout(0, UiTheme.GAP));
    private final JLabel themeReport = UiTheme.wrapped("", "error");
    // Both touched only on the EDT once the frame is shown. normalSize is the
    // last size the frame had while it was neither maximised nor minimised;
    // sizeNextStart is the size the next start would open at if nothing were
    // saved now, so a window nobody resized writes nothing.
    private Dimension normalSize;
    private Dimension sizeNextStart;

    /**
     * Builds the window without showing it. Aura still starts in the tray.
     *
     * @param toSidecar sends one command to the speech sidecar; a no-op when no
     *                  sidecar could be started, so panels may always call it
     * @param dispatch the one consumer {@code Main} shares between the tray's
     *                 dialog and a spoken utterance; the Tasks section's text box
     *                 sends through this same object rather than a second path
     *                 to {@code TaskDispatcher}
     * @param onStopAgent stops the running agent; the Tasks section's own button
     *                    is the one place for it now, moved out of Status
     * @param registry the projects Aura knows, listed with their aliases in the
     *                 Tasks section so a person can see what routing will match
     * @param logDir the folder the Log card offers to open
     * @param configFile {@code config.yaml} - read once here for the theme to start
     *                   in, the size to open at and the registry file an empty
     *                   Projects card names, then read and rewritten again by the
     *                   voice choice section's {@code Use this voice} button, by
     *                   the theme control, and by the window itself when it closes
     *                   at a new size
     */
    public AuraWindow(Consumer<Map<String, Object>> toSidecar, Consumer<String> dispatch,
                      Runnable onStopAgent, ProjectRegistry registry, Path logDir,
                      Path configFile) {
        AuraConfig settings = AuraConfig.load(configFile);
        status = new StatusPanel(toSidecar, logDir, this::hasSection, this::select);

        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setFont(UiTheme.body());
        sectionList.setBorder(UiTheme.pad(UiTheme.GAP));
        // The same rows as the voice list - hover, a selection tint with its
        // accent stripe - resting on the rail's own surface rather than on a
        // card, and a ring round the row that holds keyboard focus. The rail is
        // the first Tab stop in every section, and it has no outline of its own
        // to show focus with. JetControls.list keeps the background and
        // foreground on the palette across a switch, so refreshChrome does not
        // touch them.
        JetControls.list(sectionList, RAIL_SURFACE);
        sectionList.setCellRenderer(new JetControls.RowRenderer<String>(true) {
            @Override
            protected String text(String title) {
                return title;
            }
        });
        sectionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && sectionList.getSelectedValue() != null) {
                cards.show(body, sectionList.getSelectedValue());
            }
        });

        rail = JetControls.scrollPane(sectionList,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rail.setPreferredSize(new Dimension(RAIL_WIDTH, 0));

        themeToggle.setName("theme.toggle");
        // Loads and saves config.yaml fresh on every press rather than holding an
        // AuraConfig field across the window's lifetime, the same choice
        // VoiceChoicePanel's "Use this voice" button makes for the same reason:
        // what matters is not what this writes, it is that it leaves every other
        // setting exactly as it found it, which a fresh load-then-save guarantees
        // and a long-held stale copy would not.
        themeToggle.addActionListener(e -> {
            Theme.Mode next = Theme.mode() == Theme.Mode.DARK ? Theme.Mode.LIGHT : Theme.Mode.DARK;
            Theme.install(next);
            String problem = "";
            try {
                AuraConfig.load(configFile).withTheme(next).save(configFile);
            } catch (Exception ex) {
                log.warn("could not save the chosen theme to {}", configFile, ex);
                problem = FailureText.of(ex);
            }
            // The switch has happened either way. What a failed save changes is
            // the next start, and nothing else in the window would say so.
            themeReport.setText(problem.isEmpty() ? ""
                : UiTheme.html("Not saved to config.yaml: " + problem
                    + ". The theme resets when Aura restarts.", RAIL_WIDTH - 1 - 4 * UiTheme.GAP));
            themeReport.setToolTipText(problem.isEmpty() ? null : configFile.toString());
            themeReport.setVisible(!problem.isEmpty());
            railFoot.revalidate();
        });

        themeReport.setName("theme.report");
        themeReport.setVisible(false);
        // On a card's surface rather than the rail's: error is 4.26:1 on
        // surface.secondary in dark and 4.48:1 in light, under the 4.5:1 a
        // sentence needs, and 4.64:1 and 4.91:1 on surface.primary. The gap
        // above it is the foot's, so the button does not sit on that surface.
        themeReport.setOpaque(true);
        themeReport.setBorder(UiTheme.pad(UiTheme.GAP));
        railFoot.setOpaque(false);
        railFoot.add(themeToggle, BorderLayout.CENTER);
        railFoot.add(themeReport, BorderLayout.SOUTH);
        railColumn.add(rail, BorderLayout.CENTER);
        railColumn.add(railFoot, BorderLayout.SOUTH);

        frame.setIconImages(aura.app.TrayIconArt.windowIcons());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(railColumn, BorderLayout.WEST);
        frame.add(body, BorderLayout.CENTER);
        Dimension minimum = new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT);
        frame.setMinimumSize(minimum);
        // The size the window last closed at, fitted to this screen, or the
        // default size before it has ever been closed. The position is not
        // kept: a window opened from the tray belongs in the middle of the
        // screen, and a saved position would have to be checked against
        // screens that can come and go between two starts.
        Dimension opening = WindowSize.opening(settings.windowWidth(), settings.windowHeight(),
            new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT), minimum, usableScreen());
        frame.setSize(opening);
        frame.setLocationRelativeTo(null);
        normalSize = opening;
        sizeNextStart = opening;
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Only a size somebody dragged the window to. A maximised frame
                // reports the whole work area, and saving that would open the
                // next start at the maximised size with nothing to restore to.
                if (frame.getExtendedState() == Frame.NORMAL) {
                    normalSize = frame.getSize();
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveSize(configFile);
            }
        });

        // Focus that arrives by keyboard, or by a panel moving it, scrolls its
        // page to it. Not focus from a click: whatever the pointer reached is
        // already on screen, and moving the page under a pressed button would
        // be a surprise of its own.
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof FocusEvent focus && focus.getID() == FocusEvent.FOCUS_GAINED
                    && focus.getCause() != FocusEvent.Cause.MOUSE_EVENT
                    && SwingUtilities.isDescendingFrom(focus.getComponent(), body)) {
                ContentPane.reveal(focus.getComponent());
            }
        }, AWTEvent.FOCUS_EVENT_MASK);

        voice = new VoicePanel(toSidecar);
        // Reads and writes config.yaml directly; it never talks to the sidecar, so it
        // is added but not subscribed - there is no sidecar event this section acts on.
        voiceChoice = new VoiceChoicePanel(VoiceChoicePanel.DEFAULT_AUDITION_ROOT, configFile);
        tasks = new TasksPanel(dispatch, onStopAgent, registry, settings.projectsFile());

        addTab("Status", status);
        subscribe(status);
        addTab(VOICE_SECTION, voice);
        subscribe(voice);
        addTab("Choose a voice", voiceChoice);
        // Last, so Status stays the section a freshly opened window selects:
        // addTab picks whichever tab arrived first.
        addTab("Tasks", tasks);
        subscribe(tasks);

        // Last, after every child above is already attached to frame - not, as it
        // is tempting to write it, first. Theme.install calls
        // SwingUtilities.updateComponentTreeUI, which only walks components already
        // in a window's tree; run any earlier, and a component that is not attached
        // yet keeps whatever its look and feel gave it until the next switch.
        // Rendering both themes caught it once: a rail with the system's own blue
        // selection bar, on a window nobody had switched yet. Registered before install
        // so the same call both installs the configured mode and runs refreshChrome
        // for the first time, rather than a separate seeding path that could drift
        // from what a later switch does.
        Theme.onChange(this::refreshChrome);
        Theme.install(settings.theme());
    }

    /**
     * Re-reads {@link UiTheme}'s live accessors into the chrome this class paints
     * directly - the rail's border and viewport, the rail column's, the body's
     * and the theme report's backgrounds, and the rail foot's border - plus the
     * theme control's own label, and repaints. Called once, right after the
     * window is built, and again from every {@link Theme#onChange}, so
     * construction and a later switch produce the chrome the same way instead
     * of two code paths that could disagree.
     *
     * <p>{@code themeToggle} gets its label here and no colour at all. It is a
     * {@link JetControls#button}, which paints its own face and outline and
     * re-reads its tokens on every switch, like every other button in the
     * window, so a colour set here would only fight it. What surrounds it is
     * this class's chrome, though: the rail column's surface shows round the
     * button's rounded corners and in the padding that lines it up with the
     * section rows, and the foot's border carries the rail's separator down past
     * the button. Left to the look and feel, the column behind that padding
     * would paint the platform's light grey in both themes.
     */
    private void refreshChrome() {
        Color canvas = UiTheme.canvas();
        Color railSurface = UiTheme.color(RAIL_SURFACE);
        body.setBackground(canvas);
        rail.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.line()));
        // sectionList already covers the whole viewport (it stretches to fill it,
        // and JetControls.list keeps its background right on every switch), so this is
        // not fixing an observed defect the way TasksPanel's own activity scroll
        // needed fixing in fix round 2 - it matches the convention every other
        // scroll pane in this codebase already follows now that Theme.install no
        // longer sets Viewport.background itself, so rail does not become the
        // one exception if sectionList's coverage of it ever stops being total.
        rail.getViewport().setBackground(railSurface);
        railColumn.setBackground(railSurface);
        themeReport.setBackground(UiTheme.surface());
        railFoot.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.line()), UiTheme.pad(UiTheme.GAP)));
        themeToggle.setText(Theme.mode() == Theme.Mode.DARK
            ? "Switch to light theme" : "Switch to dark theme");
        frame.repaint();
    }

    /**
     * Writes the window's size into {@code config.yaml} as it closes, when that
     * size differs from the one the next start would open at anyway.
     *
     * <p>Through {@link AuraConfig#saveWindowSize}, which rewrites the two
     * size lines and nothing else, and only when the size has changed: a
     * window closed at the default size leaves no size in the file, so the
     * default can change in code without every config file pinning the old
     * one. A failed save is logged and nothing more. The window is closing,
     * so there is nowhere left to say it, and what it costs is one start at
     * the previous size.
     */
    private void saveSize(Path configFile) {
        Dimension size = normalSize;
        if (size.equals(sizeNextStart)) {
            return;
        }
        try {
            AuraConfig.saveWindowSize(configFile, size.width, size.height);
            sizeNextStart = size;
        } catch (Exception e) {
            log.warn("could not save the window size to {}", configFile, e);
        }
    }

    /**
     * The bounds of the screen the frame opens on, less its taskbars: the room
     * a window can take and still show its title bar and its bottom edge.
     */
    private Rectangle usableScreen() {
        GraphicsConfiguration screen = frame.getGraphicsConfiguration();
        Rectangle bounds = screen.getBounds();
        Insets taskbars = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        return new Rectangle(bounds.x + taskbars.left, bounds.y + taskbars.top,
            bounds.width - taskbars.left - taskbars.right,
            bounds.height - taskbars.top - taskbars.bottom);
    }

    /**
     * Opens the window, or raises it if it is already open.
     *
     * <p>Idempotent by construction rather than by a flag: this object owns one
     * frame, so the only thing a second call can do is un-minimise and raise it.
     * Showing a disposed frame realises it again, which is what makes
     * close-then-open work without keeping a hidden window around.
     */
    public void show() {
        onEdt(() -> {
            frame.setVisible(true);
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
            frame.toFront();
            frame.requestFocus();
            // Asked every time it opens, not only the first time: what is on disk
            // can change while the window is closed, and one JSON line is cheaper
            // than showing a stale answer to "what is missing?".
            status.refresh();
            voice.refresh();
        });
    }

    /**
     * Adds a section to the rail and the body. The first one added is selected,
     * and Ctrl with the section's place in the rail selects it from anywhere in
     * the window (see {@link SectionShortcuts}).
     */
    public void addTab(String title, JComponent panel) {
        onEdt(() -> {
            if (sections.putIfAbsent(title, panel) != null) {
                log.warn("a section named '{}' is already in the window, ignoring the second",
                    title);
                return;
            }
            sectionNames.addElement(title);
            SectionShortcuts.bind(frame.getRootPane(), sectionNames.getSize(), () -> select(title));
            body.add(panel, title);
            if (sectionNames.getSize() == 1) {
                sectionList.setSelectedIndex(0);
            }
            status.sectionsChanged();
        });
    }

    /**
     * Registers a section to receive sidecar events. Subscribers are called on
     * the event dispatch thread, in subscription order.
     *
     * <p>Separate from {@link #addTab} on purpose: a section is not obliged to
     * care about events, and deciding by {@code instanceof} which ones do would
     * turn a panel that happens to implement some other {@code Consumer} into a
     * {@code ClassCastException} at the moment the sidecar first speaks.
     */
    public void subscribe(Consumer<SidecarEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Hands one sidecar event to every subscribed section, on the EDT.
     *
     * <p>A section that throws is logged and skipped: one panel's bug must not
     * cost the others this event, nor the window the next one.
     */
    @Override
    public void accept(SidecarEvent event) {
        onEdt(() -> {
            for (Consumer<SidecarEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    log.warn("a window section threw on a '{}' event, continuing",
                        event.kind(), e);
                }
            }
        });
    }

    /**
     * Hands one agent event to the Tasks section, on the event dispatch thread.
     *
     * <p>Agent events do not arrive through {@link #subscribe} because only the
     * Tasks section acts on them today; the sidecar's own multi-listener fan-out
     * exists because Status and Voice setup both act on that stream, which is not
     * true here yet.
     */
    public void acceptAgentEvent(AgentEvent event) {
        onEdt(() -> tasks.acceptAgentEvent(event));
    }

    /**
     * Tells the Tasks section a phrase sent through {@code Main}'s shared dispatch
     * consumer reached a project, and which one. {@code Main} is the only caller:
     * it is the only place {@code TaskDispatcher}'s answer exists.
     */
    public void taskRouted(String phrase, String projectName) {
        onEdt(() -> tasks.taskRouted(phrase, projectName));
    }

    /** The counterpart to {@link #taskRouted}: the phrase did not start, and why. */
    public void taskNotStarted(String phrase, String reason) {
        onEdt(() -> tasks.taskNotStarted(phrase, reason));
    }

    /** Whether a section by that name is in the window yet. */
    public boolean hasSection(String title) {
        return sections.containsKey(title);
    }

    /**
     * Shows the named section, if it is there.
     *
     * <p>Missing is possible and is not an error: the sections arrive over
     * several releases, and a card pointing at one that does not exist yet keeps
     * its button disabled rather than offering a dead end.
     */
    public void select(String title) {
        onEdt(() -> {
            if (!sections.containsKey(title)) {
                log.warn("no section named '{}' in the window", title);
                return;
            }
            sectionList.setSelectedValue(title, true);
        });
    }

    /** Runs now if this is already the event dispatch thread, and on it if not. */
    private static void onEdt(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeLater(work);
        }
    }
}
