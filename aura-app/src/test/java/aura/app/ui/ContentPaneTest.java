package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Scrolling a section page to a component. The page is packed into a frame that is
 * never shown, because a viewport only scrolls a view that has been validated, and
 * validating needs a peer.
 */
class ContentPaneTest {

    @Test
    void revealScrollsAControlBelowTheFoldIntoViewWithItsMargin() {
        // Breaks if reveal goes: Tab walked onto buttons below the fold at the
        // window's default size and the page did not follow, so nothing on
        // screen showed where focus was.
        onEdt(() -> {
            JButton below = new JButton("Train the wake word");
            Page page = new Page(below);
            try {
                ContentPane.reveal(below);

                Rectangle seen = page.scroll.getViewport().getViewRect();
                Rectangle wanted = SwingUtilities.convertRectangle(below.getParent(), below.getBounds(),
                    page.content);
                wanted.grow(UiTheme.GAP, UiTheme.GAP);
                assertThat(seen.y).isPositive();
                assertThat(seen.contains(wanted)).as(seen + " holds " + wanted).isTrue();
            } finally {
                page.frame.dispose();
            }
        });
    }

    @Test
    void revealBringsAListInItsOwnScrollPaneIntoViewWithoutScrollingTheList() {
        // Breaks if reveal asks the list itself, which scrolls the list's own
        // viewport rather than the page the list sits on.
        onEdt(() -> {
            DefaultListModel<String> model = new DefaultListModel<>();
            for (int i = 0; i < 30; i++) {
                model.addElement("Voice " + i);
            }
            JList<String> list = new JList<>(model);
            list.setVisibleRowCount(4);
            JScrollPane inner = new JScrollPane(list);
            Page page = new Page(inner);
            try {
                ContentPane.reveal(list);

                Rectangle seen = page.scroll.getViewport().getViewRect();
                Rectangle pane = SwingUtilities.convertRectangle(inner.getParent(), inner.getBounds(),
                    page.content);
                assertThat(seen.contains(pane)).as(seen + " holds " + pane).isTrue();
                assertThat(inner.getViewport().getViewPosition().y).isZero();
            } finally {
                page.frame.dispose();
            }
        });
    }

    /** A page taller than its window, padded as every section page is, with {@code last} at its foot. */
    private static final class Page {

        final JFrame frame = new JFrame();
        final ContentPane content;
        final JScrollPane scroll;

        Page(java.awt.Component last) {
            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setBorder(UiTheme.pad(UiTheme.WIDE));
            column.add(Box.createVerticalStrut(900));
            column.add(last);
            content = new ContentPane(column);
            scroll = new JScrollPane(content);
            scroll.setPreferredSize(new Dimension(400, 300));
            frame.add(scroll);
            frame.pack();
            frame.validate();
        }
    }

    private static void onEdt(Runnable work) {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        }
    }
}
