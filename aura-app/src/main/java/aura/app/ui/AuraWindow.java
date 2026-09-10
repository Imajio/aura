package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import aura.core.AgentEvent;
import aura.core.ProjectRegistry;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
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
 * <p>The tray stays what it has always been — somewhere to glance at, and a place
 * to type a task from. It cannot answer the questions a person actually has when
 * something is not working: which models loaded, whether the wake word was ever
 * trained, why nothing is listening. Those need room, and room is a window.
 *
 * <p>Sections live in a list down the left rather than in tabs across the top.
 * Tabs would do for two short words; there will be four sections and their names
 * are phrases — "Voice setup", not "Voice" — which a tab strip either truncates
 * or scrolls.
 *
 * <h2>Threads</h2>
 *
 * <p>Sidecar events arrive on the reader thread of another process. Swing has
 * exactly one thread that may touch a realised component, and touching one from
 * anywhere else fails rarely, unreproducibly and usually somewhere unrelated —
 * the worst way to learn about a bug. Every method here that touches a component
 * therefore either runs on the event dispatch thread already or puts itself on
 * it, and every subscriber registered through {@link #subscribe} is called on
 * that thread as a consequence. Sections do not need to hop again.
 *
 * <p>The two methods that touch no component — {@link #subscribe} and {@link
 * #hasSection} — are safe from any thread instead, because they are held in
 * concurrent collections. Hopping them to the EDT would be the other answer, but
 * it would make {@code hasSection} unable to return anything.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Closing the window disposes it and leaves the process running: Aura lives in
 * the tray, and closing a window is not a request to quit. {@link #show} is
 * idempotent — there is one frame for the lifetime of this object, so a second
 * call raises the one that exists instead of opening another.
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

    static {
        // Once, before the first component exists: a look and feel set after a
        // component is built leaves that component styled the old way. Failing
        // to set it is not worth refusing to open a window over — the fallback
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
    // SidecarEvents made for the same reason — subscriptions are a handful at
    // startup, reads are on every sidecar line, and iteration must never throw
    // ConcurrentModificationException. The map is unordered because nothing reads
    // it in order: the rail's order lives in sectionNames.
    private final Map<String, JComponent> sections = new ConcurrentHashMap<>();
    private final List<Consumer<SidecarEvent>> listeners = new CopyOnWriteArrayList<>();
    private final StatusPanel status;
    private final VoicePanel voice;
    private final VoiceChoicePanel voiceChoice;
    private final TasksPanel tasks;

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
     * @param configFile {@code config.yaml} - read and rewritten by the voice choice
     *                   section's {@code Use this voice} button, nowhere else here
     */
    public AuraWindow(Consumer<Map<String, Object>> toSidecar, Consumer<String> dispatch,
                      Runnable onStopAgent, ProjectRegistry registry, Path logDir,
                      Path configFile) {
        status = new StatusPanel(toSidecar, logDir, this::hasSection, this::select);

        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setFont(UiTheme.body());
        sectionList.setFixedCellHeight(UiTheme.SECTION);
        sectionList.setBorder(UiTheme.pad(UiTheme.GAP));
        sectionList.setBackground(UiTheme.CANVAS);
        // The selection bar is painted by the look and feel and fills the whole
        // cell, so without this the name of the selected section sits flush
        // against the coloured edge and reads as cramped.
        sectionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                Component cell = super.getListCellRendererComponent(
                    list, value, index, selected, focused);
                setBorder(UiTheme.pad(UiTheme.GAP));
                return cell;
            }
        });
        sectionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && sectionList.getSelectedValue() != null) {
                cards.show(body, sectionList.getSelectedValue());
            }
        });

        JScrollPane rail = new JScrollPane(sectionList,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rail.setPreferredSize(new Dimension(RAIL_WIDTH, 0));
        rail.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, UiTheme.LINE));

        body.setBackground(UiTheme.CANVAS);

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(rail, BorderLayout.WEST);
        frame.add(body, BorderLayout.CENTER);
        frame.setSize(900, 640);
        frame.setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));
        frame.setLocationRelativeTo(null);

        voice = new VoicePanel(toSidecar);
        // Reads and writes config.yaml directly; it never talks to the sidecar, so it
        // is added but not subscribed — there is no sidecar event this section acts on.
        voiceChoice = new VoiceChoicePanel(VoiceChoicePanel.DEFAULT_AUDITION_ROOT, configFile);
        tasks = new TasksPanel(dispatch, onStopAgent, registry);

        addTab("Status", status);
        subscribe(status);
        addTab(VOICE_SECTION, voice);
        subscribe(voice);
        addTab("Choose a voice", voiceChoice);
        // Last, so Status stays the section a freshly opened window selects:
        // addTab picks whichever tab arrived first.
        addTab("Tasks", tasks);
        subscribe(tasks);
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

    /** Adds a section to the rail and the body. The first one added is selected. */
    public void addTab(String title, JComponent panel) {
        onEdt(() -> {
            if (sections.putIfAbsent(title, panel) != null) {
                log.warn("a section named '{}' is already in the window, ignoring the second",
                    title);
                return;
            }
            sectionNames.addElement(title);
            body.add(panel, title);
            if (sectionNames.getSize() == 1) {
                sectionList.setSelectedIndex(0);
            }
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
