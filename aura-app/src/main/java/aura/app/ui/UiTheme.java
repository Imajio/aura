package aura.app.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.border.Border;

/**
 * The whole visual language, in one file.
 *
 * <p>The owner asked for a window they can use "without thinking". That is not a
 * request for decoration - it is a request that every screen look the same, so
 * that a heading is always a heading and a warning always reads as one. Five
 * spacings, four type sizes and eight colours are enough for that, and a small
 * fixed set is what keeps a second panel from inventing a sixth spacing.
 */
public final class UiTheme {

    private UiTheme() { }

    /** A four-point grid. Every gap in the application is one of these. */
    public static final int TIGHT = 4;
    public static final int GAP = 8;
    public static final int SPACE = 16;
    public static final int WIDE = 24;
    public static final int SECTION = 32;

    public static final Color INK = new Color(0x1B1B1F);
    public static final Color MUTED = new Color(0x5A5D66);
    public static final Color LINE = new Color(0xD8DAE0);
    public static final Color CANVAS = new Color(0xF7F8FA);
    public static final Color ACCENT = new Color(0x2C5FE0);
    public static final Color GOOD = new Color(0x1F7A44);
    public static final Color WARN = new Color(0xA8631B);
    public static final Color BAD = new Color(0xB3261E);

    // A note wraps at this width - a CSS length, not a count of screen pixels,
    // which is the trap it was set in the first time. Swing scales an HTML px on
    // a high-DPI display: on the owner's 192dpi screen this number comes out
    // about 1.3 times bigger, and a note declared 400 laid itself out at 520
    // inside a card that had 409 to give, so three sentences were cut off mid-
    // word at the window's minimum size. The narrowest card the window can show
    // is about 410 screen pixels across - rail, scrollbar, column padding and
    // card border taken off the 720px minimum - and 300 stays inside that with
    // room for a display that scales harder than this one.
    private static final int NOTE_WIDTH = 300;

    public static Font title() {
        return base().deriveFont(Font.BOLD, 20f);
    }

    public static Font heading() {
        return base().deriveFont(Font.BOLD, 14f);
    }

    public static Font body() {
        return base().deriveFont(Font.PLAIN, 13f);
    }

    public static Font mono() {
        return new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    private static Font base() {
        return new JLabel().getFont();
    }

    public static JLabel title(String text) {
        return styled(text, title(), INK);
    }

    public static JLabel heading(String text) {
        return styled(text, heading(), INK);
    }

    public static JLabel body(String text) {
        return styled(text, body(), INK);
    }

    /** For the sentence under a control that says what it will do. */
    public static JLabel hint(String text) {
        return styled(text, body(), MUTED);
    }

    public static JLabel status(String text, Color colour) {
        return styled(text, body().deriveFont(Font.BOLD), colour);
    }

    private static JLabel styled(String text, Font font, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(colour);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * A muted note that breaks onto a second line instead of running off its card.
     *
     * <p>A plain {@code JLabel} does not wrap: it asks for however wide its one
     * line is, and a sentence that outgrows the card widens the card. Swing's own
     * wrapping is reached through HTML, which needs a width to wrap at - {@link
     * #NOTE_WIDTH} is that width, chosen to fit inside a card at the window's
     * minimum size so a note never has to be re-checked against the layout.
     *
     * <p>The text is escaped, because some of it comes from the sidecar: a
     * detail carrying a {@code <} would otherwise be read as a tag and take the
     * rest of the sentence with it.
     */
    public static JLabel wrapped(String text) {
        return hint(html(text));
    }

    /** The same wrapping, in a colour the caller chooses - a warning, usually. */
    public static JLabel wrapped(String text, Color colour) {
        JLabel label = wrapped(text);
        label.setForeground(colour);
        return label;
    }

    /**
     * The HTML a wrapped label holds, for a caller that means to reuse the
     * label and change its sentence later.
     */
    public static String html(String text) {
        return html(List.of(text));
    }

    /**
     * Several sentences in one wrapped label, each starting on its own line.
     *
     * <p>One label rather than one per line, because the number of lines is not
     * known when the panel is built: a take log grows a line per recording and a
     * list of similarities is as long as the takes on disk. Rows added and
     * removed from a card as answers arrive are rows that get left behind.
     */
    public static String html(List<String> lines) {
        StringBuilder markup = new StringBuilder("<html><body style='width:")
            .append(NOTE_WIDTH).append("px'>");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                markup.append("<br>");
            }
            markup.append(lines.get(i).replace("&", "&amp;").replace("<", "&lt;"));
        }
        return markup.append("</body></html>").toString();
    }

    /**
     * A label holding a value nobody chose the length of, made shrinkable.
     *
     * <p>A {@code JLabel} reports the width of its whole text as its minimum, and
     * {@code GridBagLayout} that cannot meet the minimum widths of its columns
     * stops laying the grid out inside the container and centres it instead -
     * which pushes column zero to a negative x, so the card's heading and its row
     * label leave the window altogether. Not clipped: gone. Saying the minimum is
     * zero lets the column shrink, and Swing then ellipsises the text to whatever
     * width is left, with the whole of it on hover.
     *
     * <p>Everything this is applied to comes from outside the window: a log path
     * the owner chose, model states the sidecar names, the reason a button is
     * off. A value a panel writes itself and knows the length of does not need it.
     */
    public static JLabel elastic(JLabel label) {
        label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
        label.setToolTipText(label.getText());
        return label;
    }

    public static Border pad(int all) {
        return BorderFactory.createEmptyBorder(all, all, all, all);
    }

    public static Border card() {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LINE), pad(SPACE));
    }

    /** Keeps a growable component from stretching to the height of the window. */
    public static <T extends Component> T capped(T component, int height) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return component;
    }
}
