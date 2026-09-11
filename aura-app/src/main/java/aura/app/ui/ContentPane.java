package aura.app.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Scrollable;

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
