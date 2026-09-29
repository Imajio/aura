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

    @Test
    void revealWhenLaidOutScrollsToASentenceJustWrittenIntoAHiddenLabel() {
        // Breaks if the reveal reads the label's bounds before the page has
        // laid the new sentence out: a hidden, empty label has no height yet,
        // and a failure written under "Train the wake word" stayed below the
        // fold at both window sizes.
        Page[] page = new Page[1];
        javax.swing.JLabel report = UiTheme.wrapped("");
        onEdt(() -> {
            report.setVisible(false);
            page[0] = new Page(report);
            report.setText(UiTheme.html("NO_FEATURE_MODELS - the openwakeword folder is empty"));
            report.setVisible(true);
            report.revalidate();
            ContentPane.revealWhenLaidOut(report);
        });
        onEdt(() -> {
            try {
                Rectangle seen = page[0].scroll.getViewport().getViewRect();
                Rectangle wanted = SwingUtilities.convertRectangle(report.getParent(), report.getBounds(),
                    page[0].content);
                assertThat(wanted.height).isPositive();
                assertThat(seen.contains(wanted)).as(seen + " holds " + wanted).isTrue();
            } finally {
                page[0].frame.dispose();
            }
        });
    }

    @Test
    void onAWideWindowTheColumnStopsAtTheReadableWidthAndKeepsToTheLeft() {
        // Breaks if the column goes back to tracking the whole viewport: at the
        // 1455 px this screen allows, a Status row's "Set this up" sat 863 px
        // from the "missing" it fixes.
        onEdt(() -> {
            JPanel column = new JPanel();
            ContentPane content = new ContentPane(column);
            JScrollPane scroll = new JScrollPane(content);
            scroll.setPreferredSize(new Dimension(ContentPane.READABLE_WIDTH + 500, 300));
            JFrame frame = new JFrame();
            try {
                frame.add(scroll);
                frame.pack();
                frame.validate();

                assertThat(content.getWidth()).as("the page still spans the viewport")
                    .isGreaterThan(ContentPane.READABLE_WIDTH);
                assertThat(column.getWidth()).isEqualTo(ContentPane.READABLE_WIDTH);
                assertThat(column.getX()).isZero();
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void belowTheReadableWidthTheColumnIsAsWideAsTheViewport() {
        // Breaks if the cap turns into a fixed width: a column wider than a
        // narrow window would put its right edge past the window's.
        onEdt(() -> {
            JPanel column = new JPanel();
            ContentPane content = new ContentPane(column);
            JScrollPane scroll = new JScrollPane(content);
            scroll.setPreferredSize(new Dimension(400, 300));
            JFrame frame = new JFrame();
            try {
                frame.add(scroll);
                frame.pack();
                frame.validate();

                assertThat(content.getWidth()).isLessThan(ContentPane.READABLE_WIDTH);
                assertThat(column.getWidth()).isEqualTo(content.getWidth());
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void aPageTakesTheCanvasOfTheThemeItIsBuiltUnderAndOfEverySwitchAfter() {
        // Breaks if page() registers its canvas listener without also running it
        // once: AuraWindow builds every section before it installs the first
        // theme, and a section that only listened kept the look and feel's own
        // background until the first switch. Breaks as well if the listener
        // stops reaching the viewport, which is what shows below a column
        // shorter than the window.
        Theme.Mode before = Theme.mode();
        try {
            for (Theme.Mode built : Theme.Mode.values()) {
                Theme.install(built);
                JPanel section = new JPanel();
                JScrollPane[] scroll = new JScrollPane[1];
                onEdt(() -> scroll[0] = ContentPane.page(section, new JPanel()));

                onEdt(() -> {
                    assertThat(section.getComponents()).containsExactly(scroll[0]);
                    assertThat(scroll[0].getBorder()).isNull();
                    assertThat(scroll[0].getVerticalScrollBar().getUnitIncrement()).isEqualTo(UiTheme.SECTION);
                    assertThat(section.getBackground()).as("built under " + built).isEqualTo(UiTheme.canvas());
                    assertThat(scroll[0].getViewport().getBackground()).as("built under " + built)
                        .isEqualTo(UiTheme.canvas());
                });

                Theme.Mode switched = built == Theme.Mode.DARK ? Theme.Mode.LIGHT : Theme.Mode.DARK;
                Theme.install(switched);
                onEdt(() -> {
                    assertThat(section.getBackground()).as("switched to " + switched).isEqualTo(UiTheme.canvas());
                    assertThat(scroll[0].getViewport().getBackground()).as("switched to " + switched)
                        .isEqualTo(UiTheme.canvas());
                });
            }
        } finally {
            Theme.install(before);
        }
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
