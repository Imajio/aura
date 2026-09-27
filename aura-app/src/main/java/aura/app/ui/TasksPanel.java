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
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
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
import javax.swing.JViewport;
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneLayout;
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

    /**
     * The selected row's accent stripe, taken out of the row's own leading
     * padding so selecting a row never moves its text. Three pixels, the
     * width of the voice list's stripe.
     */
    private static final int SELECTION_STRIPE = 3;

    private final Consumer<String> dispatch;
    private final Runnable onStopAgent;

    private final DefaultListModel<Row> rows = new DefaultListModel<>();
    // Overrides getBackground() rather than being set once, so the list keeps
    // pace with a theme switch the same as every other surface in the
    // Activity card - see activityCard() for the rest of them and for why
    // this shape was chosen over painting the fill by hand.
    private final JList<Row> activity = new JList<Row>(rows) {
        @Override
        public Color getBackground() {
            return UiTheme.surface();
        }
    };
    private boolean scrollPending;
    private final JTextField phraseField = JetControls.textField("");
    private final JButton sendButton = JetControls.button("Send");
    // dispatch.accept(phrase) now hands the work to Main's own background
    // executor and returns before anything has happened, so returning is no
    // longer proof of anything - taskRouted/taskNotStarted are what actually
    // end this, whenever Main's queued dispatch resolves.
    private boolean dispatchPending;

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
        JScrollPane scroll = JetControls.scrollPane(new ContentPane(column),
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(UiTheme.SECTION);

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);

        // A plain JPanel's and a JViewport's background both hold whatever
        // colour they were given, the same as a Border - neither has a
        // paintComponent this class can read a token from, so both are
        // re-applied from this listener instead, the way StatusPanel's and
        // VoiceChoicePanel's own canvas already are. Called once right here
        // as well as registered, the way JetControls.list, Button and
        // PlaceholderField all do: AuraWindow builds this panel before it
        // calls Theme.install, so without the immediate call this panel
        // would keep the look and feel's cached background until the first
        // switch.
        Runnable refreshCanvas = () -> {
            setBackground(UiTheme.canvas());
            scroll.getViewport().setBackground(UiTheme.canvas());
        };
        Theme.onChange(refreshCanvas);
        refreshCanvas.run();
    }

    private JComponent inputCard() {
        Card card = new Card("New task");

        JPanel row = new JPanel(new BorderLayout(UiTheme.GAP, 0));
        row.setOpaque(false);
        phraseField.setName("tasks.phrase");
        phraseField.addActionListener(e -> submit());
        row.add(phraseField, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        sendButton.setName("tasks.send");
        sendButton.addActionListener(e -> submit());
        JButton stop = JetControls.button("Stop agent");
        stop.setName("tasks.stop");
        stop.setToolTipText("Closes the running agent session. The tray has the same button.");
        stop.addActionListener(e -> onStopAgent.run());
        buttons.add(sendButton);
        buttons.add(Box.createHorizontalStrut(UiTheme.GAP));
        buttons.add(stop);
        row.add(buttons, BorderLayout.EAST);

        card.row(row);
        card.note("Name the project in the phrase, or leave it out to reuse the last one used.");
        return card;
    }

    private void submit() {
        if (dispatchPending) {
            return;
        }
        String phrase = phraseField.getText().trim();
        if (phrase.isEmpty()) {
            return;
        }
        phraseField.setText("");
        dispatchPending = true;
        sendButton.setEnabled(false);
        sendButton.setText("Sending…");
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
        // Every surface below overrides getBackground() so it reads the theme
        // live, the same override-the-read-path shape UiTheme.TokenLabel uses
        // for foreground and Card uses for its own fill and border: a colour
        // handed to setBackground is captured at that moment and keeps that
        // value through a later Theme.install. This card has five such
        // surfaces (this panel, activity, the cell renderer, the scroll pane
        // and its viewport), and every one of them needs accounting for, not
        // just the one a quick look happens to catch.
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            public Color getBackground() {
                return UiTheme.surface();
            }
        };
        card.setBorder(UiTheme.card());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(UiTheme.heading("Activity"), BorderLayout.NORTH);

        activity.setName("tasks.log");
        activity.setFont(UiTheme.body());
        // Through JetControls.list for the focus it tracks, which the outline
        // on the scroll pane below reads. The list takes Tab, and arrow keys
        // scroll it, so its focus and its selection both have to show.
        JetControls.list(activity);
        ListCellRenderer<Row> renderer = (list, value, index, selected, focused) -> {
            JLabel cell = UiTheme.body(value.text());
            cell.setForeground(UiTheme.color(value.token()));
            cell.setOpaque(true);
            // Rebuilt by the list on every paint, unlike the four surfaces
            // above and below - a plain accessor call is already live here
            // and needs no override to stay that way.
            // A selected row is marked by the stripe alone, on the row's own
            // surface. The selection tint would take the state colours under
            // their text floor: success and error measure 4.15:1 on light's
            // surface.selection and 3.69:1 and 2.90:1 on dark's.
            cell.setBackground(UiTheme.surface());
            cell.setBorder(selected
                ? BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, SELECTION_STRIPE, 0, 0, UiTheme.accent()),
                    BorderFactory.createEmptyBorder(UiTheme.TIGHT, UiTheme.TIGHT - SELECTION_STRIPE,
                        UiTheme.TIGHT, UiTheme.TIGHT))
                : UiTheme.pad(UiTheme.TIGHT));
            return cell;
        };
        activity.setCellRenderer(renderer);

        // AS_NEEDED both ways, deliberately not the never-scrolls-sideways choice
        // the cards above make. A card wraps a note at a known width; a row here
        // carries a path or a phrase of a length nobody bounded, and a horizontal
        // scrollbar loses nothing, where clipping it silently would.
        //
        // The border below has a non-zero top inset - the gap under the "Activity"
        // heading - which activity's own background cannot reach: activity
        // stretches to cover the viewport, not the scroll pane's border area, so
        // an opaque JScrollPane paints that inset in its own background. Before
        // Task 2's fix that background was the look and feel's own
        // ScrollPane.background, frozen at whichever mode installed first and
        // rendered as a solid bar that survived a switch to light and a switch
        // back to dark. Task 2's own fix pinned it to the light palette's
        // surface.primary instead, which held across a switch only because it
        // was always the light value regardless of mode - the same
        // light-in-both-themes defect this task fixes. getBackground() below reads the installed theme's surface
        // every time the scroll pane paints, the same as the viewport it
        // wraps, so the inset tracks a real switch instead of merely
        // surviving one.
        //
        // JetControls.TokenScrollPane rather than JetControls.scrollPane, for
        // the scrollbars every other scroll pane has: createViewport() runs
        // inside the JScrollPane constructor, so overriding it takes a
        // subclass, and a factory's finished pane comes too late for one.
        JScrollPane scroll = new JetControls.TokenScrollPane(activity,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
            @Override
            public Color getBackground() {
                return UiTheme.surface();
            }

            @Override
            protected JViewport createViewport() {
                return new JViewport() {
                    @Override
                    public Color getBackground() {
                        return UiTheme.surface();
                    }
                };
            }
        };
        scroll.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(UiTheme.GAP, 0, 0, 0), JetControls.focusOutline(activity)));
        // A handful of rows, always, whatever sits above this card on the page.
        // Rendering the window at its minimum with only one project registered
        // found the alternative: the page's own scroll left this card a single
        // row tall, which is this section's one feature not working at the size
        // the window is allowed to be. FontMetrics rather than a rendered
        // component's own preferred size, because that measurement is reliable
        // before this list has a peer, where a component's is not.
        //
        // Exactly VISIBLE_ROWS rows of viewport, not VISIBLE_ROWS rows of
        // pane: the gap under the heading and the horizontal scrollbar come on
        // top. Room for the scrollbar is kept whether it shows or not, and
        // WholeRows trims the viewport back to whole rows when it does not.
        int rowHeight = activity.getFontMetrics(UiTheme.body()).getHeight() + 2 * UiTheme.TIGHT;
        activity.setFixedCellHeight(rowHeight);
        scroll.setLayout(new WholeRows(rowHeight));
        Insets insets = scroll.getInsets();
        scroll.setPreferredSize(new Dimension(0, insets.top + insets.bottom
            + rowHeight * VISIBLE_ROWS + scroll.getHorizontalScrollBar().getPreferredSize().height));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /**
     * The Activity list's scroll pane layout: the usual one, with the viewport
     * and the vertical scrollbar then trimmed to a whole number of rows.
     *
     * <p>The list scrolls to its newest row, so the viewport's top edge falls
     * wherever the rows above happen to end. A viewport a few pixels taller
     * than a whole number of rows showed a sliver of the row above: the
     * descenders of a line with nothing over them, floating under the heading
     * like stray marks. What the trim takes off stays blank card surface
     * under the last row.
     *
     * <p>The usual layout sizes the viewport untrimmed first, and a list
     * scrolled to its end is pulled back to fit that taller viewport as it
     * does. Trimmed without undoing that, the newest row lost its bottom and
     * a sliver of the row above came back. So the view goes back to where it
     * was before the layout, as far as the trimmed viewport allows.
     */
    private static final class WholeRows extends ScrollPaneLayout {

        private final int rowHeight;

        WholeRows(int rowHeight) {
            this.rowHeight = rowHeight;
        }

        @Override
        public void layoutContainer(Container parent) {
            if (viewport == null) {
                super.layoutContainer(parent);
                return;
            }
            Point before = viewport.getViewPosition();
            super.layoutContainer(parent);
            Rectangle port = viewport.getBounds();
            int whole = port.height / rowHeight * rowHeight;
            if (whole == 0 || whole == port.height) {
                return;
            }
            viewport.setSize(port.width, whole);
            if (vsb != null && vsb.isVisible()) {
                vsb.setSize(vsb.getWidth(), whole);
            }
            if (hsb != null && hsb.isVisible()) {
                hsb.setLocation(hsb.getX(), port.y + whole);
            }
            Component view = viewport.getView();
            if (view != null) {
                int end = Math.max(0, view.getPreferredSize().height - whole);
                viewport.setViewPosition(new Point(viewport.getViewPosition().x, Math.min(before.y, end)));
            }
        }
    }

    /**
     * Reports how the phrase just sent through the shared dispatch consumer was
     * routed. Called by {@link AuraWindow} with exactly what {@code Main} learned
     * from {@code TaskDispatcher}, whether the phrase was typed here, typed into
     * the tray, or spoken.
     */
    void taskRouted(String phrase, String projectName) {
        addRow("Task: " + phrase, "text.primary");
        addRow("Routed to project " + projectName, "success");
        endDispatch();
    }

    /** The counterpart to {@link #taskRouted}: the phrase went nowhere, and why. */
    void taskNotStarted(String phrase, String reason) {
        addRow("Task: " + phrase, "text.primary");
        addRow(reason, "error");
        endDispatch();
    }

    /**
     * Restores Send once a dispatch answers, however it answers.
     *
     * <p>Both callers above are Main's own two outcomes for whichever phrase it
     * queued, from any of the three sources that share {@code dispatch} - the
     * tray, a spoken utterance, or this panel's own Send. Clearing the flag on
     * either one, regardless of source, keeps this in step with dispatchPending
     * even when a dispatch this panel did not start is what finishes first.
     */
    private void endDispatch() {
        dispatchPending = false;
        sendButton.setEnabled(true);
        sendButton.setText("Send");
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
                addRow("Failed: " + why, "error");
            } else {
                addRow("Finished", "success");
            }
            return;
        }
        addRow(rowText(event), "text.primary");
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
            addRow("Narration: " + event.text("text"), "text.secondary");
        }
    }

    private void addRow(String text, String token) {
        rows.addElement(new Row(text, token));
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

    /** One line of the transcript: what it says, and which token says how it went. */
    record Row(String text, String token) { }

    private static JComponent leftAligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
