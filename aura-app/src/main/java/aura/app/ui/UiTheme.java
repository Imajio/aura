package aura.app.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.border.Border;

/**
 * The whole visual language, in one file.
 *
 * <p>The owner asked for a window they can use "without thinking". That is not a
 * request for decoration — it is a request that every screen look the same, so
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
