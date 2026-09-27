package aura.app.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * The scrollable view: a column of cards at their natural height, never wider
 * than the window.
 *
 * <p>{@code NORTH} takes the column's preferred height and leaves the rest
 * empty, which is what makes four short cards sit at the top instead of four
 * tall ones sharing out the screen. Tracking the viewport width is the other
 * half: without it the view is laid out at its own preferred width, so one
 * over-long line in one card widens every card past the right edge of the
 * window - and with no horizontal scrollbar, what is past the edge is simply
 * gone. Bounded this way the worst a long line can do is get clipped itself.
 */
final class ContentPane extends JPanel implements Scrollable {

    ContentPane(JComponent content) {
        super(new BorderLayout());
        setOpaque(false);
        add(content, BorderLayout.NORTH);
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
}
