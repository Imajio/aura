package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Dimension;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * What is worth asserting about a visual language.
 *
 * <p>Not that a colour equals a hex value — that only detects change, and every
 * palette edit would then have to be made twice. What is asserted here is the
 * set of relations the design depends on: that the grid has distinct rungs, that
 * a hint is quieter than body text, that a status stands out, and that the four
 * state colours can be told apart. Each of those is a property a later edit
 * could break while the application still compiles and still looks plausible in
 * a screenshot, which is exactly the kind of breakage a test is for. The comment
 * on each test names the production change it catches.
 */
class UiThemeTest {

    @Test
    void theSpacingGridIsDistinctAndAscending() {
        // Breaks if a spacing is given a value another one already has (say
        // GAP = 4, colliding with TIGHT), or if two of them are swapped. Either
        // leaves five named constants that no longer mean five different gaps,
        // and a panel asking for WIDE would silently get SPACE's spacing.
        List<Integer> grid = List.of(
            UiTheme.TIGHT, UiTheme.GAP, UiTheme.SPACE, UiTheme.WIDE, UiTheme.SECTION);

        assertThat(grid).doesNotHaveDuplicates();
        assertThat(grid).isSorted();
        assertThat(UiTheme.TIGHT).isPositive();
    }

    @Test
    void aHintIsQuieterThanBodyText() {
        // Breaks if hint() is changed to use INK — the sentence under a control
        // would then read as loudly as the control's own label, and the reader
        // loses the cue that says "this is explanation, not content". Asserted
        // as a relation rather than a hex value so that repainting the palette
        // does not have to touch this test, while colouring a hint like body
        // text still fails it.
        JLabel body = UiTheme.body("Sidecar");
        JLabel hint = UiTheme.hint("what the sidecar reported when it started");

        assertThat(hint.getForeground()).isNotEqualTo(body.getForeground());
        assertThat(brightness(hint.getForeground()))
            .as("a hint is lighter than the ink it explains")
            .isGreaterThan(brightness(body.getForeground()));
    }

    @Test
    void aStatusWordIsBoldAndBodyTextIsNot() {
        // Breaks if the deriveFont(Font.BOLD) is dropped from status(). The
        // state word — "running", "missing" — is the one word on a card a person
        // scans for, and unbolded it sits at the same weight as the label beside
        // it. Body is asserted plain in the same test so that making *everything*
        // bold does not quietly satisfy the first half.
        JLabel status = UiTheme.status("running", UiTheme.GOOD);
        JLabel body = UiTheme.body("running");

        assertThat(status.getFont().isBold()).isTrue();
        assertThat(body.getFont().isBold()).isFalse();
    }

    @Test
    void theStateColoursCanBeToldApartFromEachOtherAndFromInk() {
        // Breaks if two state colours are collapsed onto one value — WARN set to
        // BAD's red, say, so "missing" and "broken" become the same signal — or
        // if any of them is set to INK, which would make a coloured state word
        // indistinguishable from ordinary text and undo the point of colouring
        // it at all.
        List<Color> states = List.of(
            UiTheme.ACCENT, UiTheme.GOOD, UiTheme.WARN, UiTheme.BAD);

        assertThat(states).doesNotHaveDuplicates();
        assertThat(states).doesNotContain(UiTheme.INK);
    }

    @Test
    void aCappedComponentIsBoundedInHeightOnlyAndIsStillTheSameComponent() {
        // Breaks if capped() is written as new Dimension(height, height), which
        // pins the width too: every card in a BoxLayout column would then shrink
        // to a narrow strip instead of filling the window. Also breaks if capped
        // stops returning its argument, which would make the idiomatic
        // add(capped(panel, 80)) add nothing.
        JPanel panel = new JPanel();

        JPanel returned = UiTheme.capped(panel, 80);

        assertThat(returned).isSameAs(panel);
        assertThat(panel.getMaximumSize()).isEqualTo(new Dimension(Integer.MAX_VALUE, 80));
    }

    @Test
    void theTypeScaleDescendsFromTitleToBody() {
        // Breaks if title() and heading() are given the same size, which is how
        // a window ends up with no visible hierarchy: the section title stops
        // outranking the card headings under it, and a person has to read the
        // words to find out where they are.
        assertThat(UiTheme.title().getSize()).isGreaterThan(UiTheme.heading().getSize());
        assertThat(UiTheme.heading().getSize()).isGreaterThan(UiTheme.body().getSize());
    }

    /** Perceived lightness, so "quieter" is a measurable claim rather than a hex diff. */
    private static double brightness(Color colour) {
        return 0.299 * colour.getRed() + 0.587 * colour.getGreen() + 0.114 * colour.getBlue();
    }
}
