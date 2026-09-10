package aura.app.ui;

import aura.app.SidecarEvents.SidecarEvent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.Project;
import aura.core.ProjectRegistry;
import aura.core.ToolClass;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;

/**
 * The section that dispatches a task and follows it.
 *
 * <p>The tray can start a task; it cannot show one running. This section takes the
 * same phrase the tray's dialog takes, sends it through {@code Main}'s one shared
 * dispatch consumer, and renders what happens next: which project the phrase
 * routed to, every tool the agent runs, the narration lines, and how the task
 * ends. A dedicated section rather than the tray growing a log is the point of the
 * window at all - the tray is a glance, this is where the work is watched.
 *
 * <p>{@code Stop agent} lives here rather than in the Status section it started
 * in. Status had it only because {@link AuraWindow}'s constructor already took an
 * {@code onStopAgent} and no card wanted it yet; the button belongs beside the
 * task it stops.
 *
 * <h2>One shared path</h2>
 *
 * <p>Dispatching is one call - {@code Main}'s {@code dispatch} consumer, the same
 * object the tray's dialog and a spoken utterance both use. This panel does not
 * call {@code TaskDispatcher} itself and does not decide which project a phrase
 * means; a second opinion on routing is how the tray and the window would end up
 * disagreeing about what a phrase does. Which project a phrase actually reached is
 * something only {@code Main} learns, from {@code TaskDispatcher}'s return value,
 * so {@code Main} reports it back here through {@link #taskRouted} and
 * {@link #taskNotStarted} - the same information the tray receives as a balloon,
 * addressed to this panel instead.
 *
 * <h2>A failure must read as a failure</h2>
 *
 * <p>Commit {@code a6aa624} fixed a {@code DONE} event that was not {@code ok}
 * closing a task silently, indistinguishable from success. {@link #acceptAgentEvent}
 * repeats the exact check that fix made - {@code Boolean.FALSE.equals(event.ok())} -
 * and renders the two outcomes as different words, "Finished" against
 * "Failed: …", not merely different colours on the same word. A colour is missed
 * by a glance and lost entirely on a colour-blind reader; the word is not.
 *
 * <h2>Bounded, because the day is long</h2>
 *
 * <p>Every row - a task line, a tool step, a narration line - is added to a
 * {@link DefaultListModel} capped at {@link #MAX_ROWS}. Past the cap the oldest
 * row is dropped, not the newest: an application left running all day is watched
 * for what it is doing now, not for the first thing it ever did.
 *
 * <h2>Threads</h2>
 *
 * <p>Every method here that touches a component expects to already be on the
 * event dispatch thread. User input arrives there on its own, as every Swing
 * event does; {@link AuraWindow} puts the sidecar's events and the agent's events
 * on it before calling in, the same guarantee {@link StatusPanel} and
 * {@link VoicePanel} rely on.
 */
public final class TasksPanel extends JPanel implements Consumer<SidecarEvent> {

    /**
     * The last few hundred rows, not the whole day. Named rather than inlined so
     * the test that proves the trim can drive past it by an exact number.
     */
    static final int MAX_ROWS = 500;

    /** The Activity card's own height, in rows, whatever else is on the page. */
    private static final int VISIBLE_ROWS = 6;

    private final Consumer<String> dispatch;
    private final Runnable onStopAgent;

    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    private final JList<Row> activity = new JList<>(rows);
    private boolean scrollPending;
    private final JTextField phraseField = new JTextField();

    TasksPanel(Consumer<String> dispatch, Runnable onStopAgent, ProjectRegistry registry) {
        this.dispatch = dispatch;
        this.onStopAgent = onStopAgent;

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(UiTheme.pad(UiTheme.WIDE));

        column.add(leftAligned(UiTheme.title("Tasks")));
        column.add(Box.createVerticalStrut(UiTheme.TIGHT));
        column.add(leftAligned(UiTheme.wrapped(
            "Send a task in plain language, then watch it work: which project it "
                + "went to, every tool it runs, and how it ends.")));
        column.add(Box.createVerticalStrut(UiTheme.WIDE));
        column.add(inputCard());
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(projectsCard(registry));
        column.add(Box.createVerticalStrut(UiTheme.SPACE));
        column.add(activityCard());

        // The whole column scrolls, the same choice StatusPanel and VoicePanel
        // make: a card that would otherwise starve for height at the window's
        // minimum instead pushes the page down, reachable by scrolling, rather
        // than being squeezed into a sliver nobody could read. The Activity
        // card keeps its own inner scroll besides, sized to a handful of rows
        // that stays constant however tall the page above it grows.
        JScrollPane scroll = new JScrollPane(new ContentPane(column),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(UiTheme.CANVAS);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        setBackground(UiTheme.CANVAS);
        add(scroll, BorderLayout.CENTER);
    }

    private JComponent inputCard() {
        Card card = new Card("New task");

        JPanel row = new JPanel(new BorderLayout(UiTheme.GAP, 0));
        row.setOpaque(false);
        phraseField.setName("tasks.phrase");
        phraseField.setFont(UiTheme.body());
        phraseField.addActionListener(e -> submit());
        row.add(phraseField, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        JButton send = button("Send");
        send.setName("tasks.send");
        send.addActionListener(e -> submit());
        JButton stop = button("Stop agent");
        stop.setName("tasks.stop");
        stop.setToolTipText("Closes the running agent session. The tray has the same button.");
        stop.addActionListener(e -> onStopAgent.run());
        buttons.add(send);
        buttons.add(Box.createHorizontalStrut(UiTheme.GAP));
        buttons.add(stop);
        row.add(buttons, BorderLayout.EAST);

        card.row(row);
        card.note("Name the project in the phrase, or leave it out to reuse the last one used.");
        return card;
    }

    private void submit() {
        String phrase = phraseField.getText().trim();
        if (phrase.isEmpty()) {
            return;
        }
        phraseField.setText("");
        dispatch.accept(phrase);
    }

    private static JComponent projectsCard(ProjectRegistry registry) {
        Card card = new Card("Projects");
        if (registry.all().isEmpty()) {
            card.note("No projects in the registry.");
            return card;
        }
        for (Project project : registry.all()) {
            boolean hasAliases = !project.aliases().isEmpty();
            String aliasText = hasAliases ? String.join(", ", project.aliases()) : "no aliases";
            JLabel aliasLabel = hasAliases ? UiTheme.body(aliasText) : UiTheme.hint(aliasText);
            // The registry names and aliases are strings nobody here chose the
            // length of. Elastic is what stops a long one pushing this card's
            // grid into the negative x that UiTheme.elastic's own note warns
            // about, instead of just shrinking the value with the rest on hover.
            card.line(project.name(), UiTheme.elastic(aliasLabel), null);
        }
        return card;
    }

    private JComponent activityCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(UiTheme.card());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiTheme.heading("Activity"), BorderLayout.NORTH);

        activity.setName("tasks.log");
        activity.setFont(UiTheme.body());
        activity.setBackground(Color.WHITE);
        ListCellRenderer<Row> renderer = (list, value, index, selected, focused) -> {
            JLabel cell = UiTheme.body(value.text());
            cell.setForeground(value.colour());
            cell.setOpaque(true);
            cell.setBackground(Color.WHITE);
            cell.setBorder(UiTheme.pad(UiTheme.TIGHT));
            return cell;
        };
        activity.setCellRenderer(renderer);

        // AS_NEEDED both ways, deliberately not the never-scrolls-sideways choice
        // the cards above make. A card wraps a note at a known width; a row here
        // carries a path or a phrase of a length nobody bounded, and a horizontal
        // scrollbar loses nothing, where clipping it silently would.
        JScrollPane scroll = new JScrollPane(activity,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder(UiTheme.GAP, 0, 0, 0));
        // A handful of rows, always, whatever sits above this card on the page.
        // Rendering the window at its minimum with only one project registered
        // found the alternative: the page's own scroll left this card a single
        // row tall, which is this section's one feature not working at the size
        // the window is allowed to be. FontMetrics rather than a rendered
        // component's own preferred size, because that measurement is reliable
        // before this list has a peer, where a component's is not.
        int rowHeight = activity.getFontMetrics(UiTheme.body()).getHeight() + 2 * UiTheme.TIGHT;
        scroll.setPreferredSize(new Dimension(0, rowHeight * VISIBLE_ROWS));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /**
     * Reports how the phrase just sent through the shared dispatch consumer was
     * routed. Called by {@link AuraWindow} with exactly what {@code Main} learned
     * from {@code TaskDispatcher}, whether the phrase was typed here, typed into
     * the tray, or spoken.
     */
    void taskRouted(String phrase, String projectName) {
        addRow("Task: " + phrase, UiTheme.INK);
        addRow("Routed to project " + projectName, UiTheme.GOOD);
    }

    /** The counterpart to {@link #taskRouted}: the phrase went nowhere, and why. */
    void taskNotStarted(String phrase, String reason) {
        addRow("Task: " + phrase, UiTheme.INK);
        addRow(reason, UiTheme.BAD);
    }

    /** One row per agent event, on the thread {@link AuraWindow} already put it on. */
    void acceptAgentEvent(AgentEvent event) {
        if (event.kind() == EventKind.DONE) {
            // The exact check a6aa624 added to Main's own sink. A DONE that is not
            // ok must read as a failure here too, or the defect that fix closed
            // simply reopens on this surface instead.
            if (Boolean.FALSE.equals(event.ok())) {
                String why = event.summaryHint().isBlank()
                    ? "the agent gave no reason" : event.summaryHint();
                addRow("Failed: " + why, UiTheme.BAD);
            } else {
                addRow("Finished", UiTheme.GOOD);
            }
            return;
        }
        addRow(rowText(event), UiTheme.INK);
    }

    private static String rowText(AgentEvent event) {
        StringBuilder text = new StringBuilder(event.kind().toString());
        if (event.toolClass() != ToolClass.OTHER) {
            text.append(' ').append(event.toolClass());
        }
        if (!event.target().isBlank()) {
            text.append(' ').append(event.target());
        }
        return text.toString();
    }

    @Override
    public void accept(SidecarEvent event) {
        if ("narration".equals(event.kind())) {
            addRow("Narration: " + event.text("text"), UiTheme.MUTED);
        }
    }

    private void addRow(String text, Color colour) {
        rows.addElement(new Row(text, colour));
        while (rows.getSize() > MAX_ROWS) {
            rows.remove(0);
        }
        // Coalesced rather than called here directly. A verbose task can hand
        // this dozens of events within one paint cycle, and scrolling on every
        // single one of them, measured while rendering the over-the-cap state,
        // turned a few hundred rows into a multi-minute stall on the event
        // dispatch thread - the one thread this whole window depends on. One
        // scroll per burst, after the model has settled, costs nothing visible
        // and still lands the view on the newest row.
        if (!scrollPending) {
            scrollPending = true;
            SwingUtilities.invokeLater(() -> {
                scrollPending = false;
                int last = rows.getSize() - 1;
                if (last >= 0) {
                    activity.ensureIndexIsVisible(last);
                }
            });
        }
    }

    /** One line of the transcript: what it says, and what colour says how it went. */
    record Row(String text, Color colour) { }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(UiTheme.body());
        button.setFocusPainted(false);
        return button;
    }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
