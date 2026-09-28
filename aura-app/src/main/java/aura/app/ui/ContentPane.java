package aura.app.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * The scrollable view: a column of cards at their natural height, never wider
 * than the window and never wider than {@link #READABLE_WIDTH}.
 *
 * <p>The column takes its preferred height and leaves the rest empty, which is
 * what makes four short cards sit at the top instead of four tall ones sharing
 * out the screen. Tracking the viewport width is the other half: without it the
 * view is laid out at its own preferred width, so one over-long line in one card
 * widens every card past the right edge of the window - and with no horizontal
 * scrollbar, what is past the edge is simply gone. Bounded this way the worst a
 * long line can do is get clipped itself.
 */
final class ContentPane extends JPanel implements Scrollable {

    /**
     * The widest a section's column grows, its padding included. A wider window
     * leaves the extra width empty to the right of the column.
     *
     * <p>One cap for the page rather than one per card. Every card on a page
     * spans the column, so capping the column keeps them one width, and a row's
     * button stays within reach of the value it acts on: at the 1455 px this
     * screen allows, an uncapped Status row put "Set this up" 863 px from the
     * "missing" it fixes, and the ten line buttons in Choose a voice each
     * stretched to half of the card.
     *
     * <p>720 because it is a round step just past everything the window was
     * built and audited at. The widest page asks for 646 (Status, whose Log row
     * holds this machine's log path whole beside its button), and the default
     * 900 px window gives the column 678, or 688 on a page with no scrollbar,
     * so none of those is cut or squeezed. Past that, every pixel of cap only
     * moves buttons further from their values; 720 leaves some headroom for a
     * longer log path or a longer reason before that row ellipsises or wraps.
     */
    static final int READABLE_WIDTH = 720;

    ContentPane(JComponent content) {
        super(new ReadableColumn());
        setOpaque(false);
        add(content);
    }

    /**
     * Scrolls the page that holds {@code component} until the component, and
     * {@link UiTheme#GAP} round it, are in view. Does nothing for a component
     * that is not on a page, or not visible.
     *
     * <p>Swing does not do this by itself. A page is taller than the window at
     * the default size, and Tab walked onto buttons below the fold with nothing
     * on screen showing where focus had gone. The margin keeps a focus ring
     * drawn at a control's edge from being clipped with it.
     *
     * <p>A component inside a scroll pane of its own - the voice list, the
     * Activity list - brings that whole pane into view, and a spinner's editor
     * brings the spinner, since that is where each one shows its focus. Asking
     * the list itself would scroll the list, not the page.
     */
    static void reveal(Component component) {
        ContentPane page = (ContentPane) SwingUtilities.getAncestorOfClass(ContentPane.class, component);
        if (page == null || !component.isVisible()) {
            return;
        }
        Component target = component;
        for (Component at = component.getParent(); at != null && at != page; at = at.getParent()) {
            if (at instanceof JScrollPane || at instanceof JSpinner) {
                target = at;
            }
        }
        Rectangle bounds = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), page);
        bounds.grow(UiTheme.GAP, UiTheme.GAP);
        page.scrollRectToVisible(bounds);
    }

    /**
     * The same, once the page has laid out the change its caller just made:
     * a sentence written into a label that was empty, or hidden, is still
     * sized for its old text until the next layout, and bounds read now would
     * scroll to where the label used to end.
     */
    static void revealWhenLaidOut(Component component) {
        SwingUtilities.invokeLater(() -> {
            ContentPane page = (ContentPane) SwingUtilities.getAncestorOfClass(ContentPane.class, component);
            JScrollPane pane = page == null ? null
                : (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, page);
            if (pane != null) {
                pane.validate();
            }
            reveal(component);
        });
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return UiTheme.SECTION;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return visible.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /**
     * Lays the one column out from the left edge, at the page's width up to
     * {@link #READABLE_WIDTH}, and at its preferred height.
     *
     * <p>Left-aligned, not centred: the rail sits on the left and each card's
     * rows read from the left, so a centred column would move every name and
     * value sideways each time the window is resized past the cap. The sizes
     * the page asks for are the column's own, so a scroll pane still scrolls
     * to the column's full height and a page still reports what it needs.
     */
    private static final class ReadableColumn implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) { }

        @Override
        public void removeLayoutComponent(Component component) { }

        @Override
        public Dimension preferredLayoutSize(Container page) {
            return withInsets(page, column(page).getPreferredSize());
        }

        @Override
        public Dimension minimumLayoutSize(Container page) {
            return withInsets(page, column(page).getMinimumSize());
        }

        @Override
        public void layoutContainer(Container page) {
            Insets insets = page.getInsets();
            Component column = column(page);
            int room = page.getWidth() - insets.left - insets.right;
            column.setBounds(insets.left, insets.top, Math.min(room, READABLE_WIDTH),
                column.getPreferredSize().height);
        }

        private static Component column(Container page) {
            return page.getComponent(0);
        }

        private static Dimension withInsets(Container page, Dimension column) {
            Insets insets = page.getInsets();
            return new Dimension(column.width + insets.left + insets.right,
                column.height + insets.top + insets.bottom);
        }
    }
}
