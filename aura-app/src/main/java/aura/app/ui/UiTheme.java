package aura.app.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

/**
 * The whole visual language, in one file.
 *
 * <p>The owner asked for a window they can use "without thinking". That is not a
 * request for decoration - it is a request that every screen look the same, so
 * that a heading is always a heading and a warning always reads as one.
 *
 * <p>Since M4 the palette itself comes from jet-swing-design-system's token
 * layer: two maps, one per {@link Theme.Mode}, holding the colours {@code
 * TOKENS.md} lists, under exactly its names. Three values differ from the
 * document on purpose: the light palette's {@code success}, {@code warning}
 * and {@code error} are darker shades of the document's hues, because the
 * document's single value for both themes is too faint to read as text on a
 * white card. The comment above them has the measurements. {@link #color(String)} reads
 * whichever map {@link Theme#mode()} says is current; the named accessors
 * below it - {@link #ink()}, {@link #muted()} and the rest - read the same
 * map through the same lookup, so a call site that means "the muted text
 * colour" is a compile error rather than a string a typo can slip through
 * unnoticed.
 *
 * <p>The five spacing constants under the "pre-M4 spacing" heading below are
 * the names the panels were written against, each one a step on the same grid
 * {@link #space(int)} checks. The fixed colours that used to sit beside them,
 * pinned to the light palette, are gone: every panel now reads colour through
 * the accessors above, so a theme switch reaches all of it.
 */
public final class UiTheme {

    private UiTheme() { }

    // Dark and light values in the same order TOKENS.md lists them. Every
    // token the document names appears in both maps, whether or not Aura
    // reads it yet; a token added to one without its twin in the other is
    // exactly what UiThemeTest's palette agreement test is for. Every value
    // is the document's except light success, warning and error; the comment
    // above those three says why.
    static final Map<String, Color> DARK_PALETTE = Map.ofEntries(
        Map.entry("surface.app", hex("#191A1C")),
        Map.entry("surface.primary", hex("#1E1F22")),
        Map.entry("surface.secondary", hex("#25262A")),
        Map.entry("surface.raised", hex("#2B2D30")),
        Map.entry("surface.input", hex("#202124")),
        // Aura has no editor surface - no component paints one - but
        // TOKENS.md defines this token and the two palettes must agree on
        // every token the document defines, not only the ones Aura happens
        // to use yet. Kept on purpose; not dead.
        Map.entry("surface.editor", hex("#1E1F22")),
        Map.entry("surface.selection", hex("#34415A")),
        Map.entry("border.subtle", hex("#35373B")),
        Map.entry("border.default", hex("#45474D")),
        Map.entry("border.focus", hex("#4D8DFF")),
        Map.entry("text.primary", hex("#E7E9EA")),
        Map.entry("text.secondary", hex("#A8ABB2")),
        Map.entry("text.tertiary", hex("#7D8088")),
        Map.entry("text.disabled", hex("#62656C")),
        Map.entry("text.link", hex("#5E9BFF")),
        Map.entry("accent.primary", hex("#4D8DFF")),
        Map.entry("accent.hover", hex("#649DFF")),
        Map.entry("accent.pressed", hex("#3E77DD")),
        Map.entry("success", hex("#4CAF73")),
        Map.entry("warning", hex("#D9A441")),
        Map.entry("error", hex("#E35B5B")),
        Map.entry("info", hex("#4D8DFF")));

    static final Map<String, Color> LIGHT_PALETTE = Map.ofEntries(
        Map.entry("surface.app", hex("#F7F8FA")),
        Map.entry("surface.primary", hex("#FFFFFF")),
        Map.entry("surface.secondary", hex("#F1F2F4")),
        Map.entry("surface.raised", hex("#FFFFFF")),
        Map.entry("surface.input", hex("#FFFFFF")),
        // See the matching comment in DARK_PALETTE: Aura has no editor
        // surface, this token is kept because TOKENS.md defines it.
        Map.entry("surface.editor", hex("#FFFFFF")),
        Map.entry("surface.selection", hex("#DDE8FF")),
        Map.entry("border.subtle", hex("#E3E5E8")),
        Map.entry("border.default", hex("#C8CBD1")),
        Map.entry("border.focus", hex("#4B7BEC")),
        Map.entry("text.primary", hex("#202124")),
        Map.entry("text.secondary", hex("#5F6368")),
        Map.entry("text.tertiary", hex("#7A7F87")),
        Map.entry("text.disabled", hex("#A4A8AE")),
        Map.entry("text.link", hex("#356AE6")),
        Map.entry("accent.primary", hex("#4D8DFF")),
        Map.entry("accent.hover", hex("#649DFF")),
        Map.entry("accent.pressed", hex("#3E77DD")),
        // The three values in either map that are not TOKENS.md's. The
        // document gives success #4CAF73, warning #D9A441 and error #E35B5B
        // once, for both themes, and on the dark card they read: 6.03:1,
        // 7.33:1 and 4.64:1 on surface.primary. On the light card they measure
        // 2.73:1, 2.25:1 and 3.55:1, and every place Aura uses any of them it
        // uses it as the ink of 13 px text - a state word such as "missing" or
        // "running", a sentence such as the microphone warning, or a failed
        // task's reason - which needs 4.5:1. These are the same three hues
        // made darker, each to the lightest shade that still clears 4.5:1 on
        // surface.secondary, the darkest light surface, or for error one step
        // past it, for the same margin the other two have: success 5.11:1,
        // warning 5.19:1 and error 5.11:1 on surface.primary, 4.81:1, 4.88:1
        // and 4.81:1 on surface.app, 4.56:1 for error on surface.secondary.
        // The dark palette keeps the document's values.
        Map.entry("success", hex("#367B51")),
        Map.entry("warning", hex("#8D661C")),
        Map.entry("error", hex("#D52424")),
        Map.entry("info", hex("#4D8DFF")));

    private static Color hex(String value) {
        return Color.decode(value);
    }

    /**
     * The design system's own contract: a token name from {@code TOKENS.md},
     * resolved against whichever mode {@link Theme#mode()} currently reports.
     * Throws rather than guessing when the name is not one either palette
     * defines, the same way a misspelled map key should fail loudly instead
     * of quietly returning nothing.
     */
    public static Color color(String token) {
        Map<String, Color> palette = Theme.mode() == Theme.Mode.DARK ? DARK_PALETTE : LIGHT_PALETTE;
        Color value = palette.get(token);
        if (value == null) {
            throw new IllegalArgumentException("Unknown token: " + token);
        }
        return value;
    }

    // Named accessors so a call site is compile-checked instead of relying on
    // a string. Each delegates to color(String) rather than reading the
    // palettes directly, so the two APIs cannot drift apart.
    public static Color ink() { return color("text.primary"); }
    public static Color muted() { return color("text.secondary"); }
    public static Color line() { return color("border.subtle"); }
    public static Color canvas() { return color("surface.app"); }
    public static Color surface() { return color("surface.primary"); }
    public static Color accent() { return color("accent.primary"); }
    public static Color good() { return color("success"); }
    public static Color warn() { return color("warning"); }
    public static Color bad() { return color("error"); }

    private static final Set<Integer> SPACING_STEPS =
        Set.of(0, 4, 8, 12, 16, 20, 24, 32, 40, 48);

    /**
     * Validates a spacing step against {@code TOKENS.md}'s own grid rather
     * than inventing one: a step the design system does not list throws
     * instead of being handed back anyway.
     */
    public static int space(int step) {
        if (!SPACING_STEPS.contains(step)) {
            throw new IllegalArgumentException("Unsupported spacing step: " + step);
        }
        return step;
    }

    public static int compactControlHeight() { return 28; }

    public static int controlHeight() { return 32; }

    public static int largeControlHeight() { return 36; }

    private static final Set<Integer> RADII = Set.of(0, 4, 6, 8);

    /**
     * Validates a corner radius against {@code TOKENS.md}'s own set rather
     * than inventing one, the same shape as {@link #space(int)}: a radius
     * the design system does not list throws instead of being handed back
     * anyway.
     */
    public static int radius(int px) {
        if (!RADII.contains(px)) {
            throw new IllegalArgumentException("Unsupported radius: " + px);
        }
        return px;
    }

    // ---- pre-M4 spacing, still read by the panels. See the class comment. ----

    /** A four-point grid. Every gap in the application is one of these. */
    public static final int TIGHT = 4;
    public static final int GAP = 8;
    public static final int SPACE = 16;
    public static final int WIDE = 24;
    public static final int SECTION = 32;

    // A note wraps at this width - a CSS length, not a count of screen pixels,
    // which is the trap it was set in the first time. javax.swing.text.html's
    // CSS class hardcodes one CSS "px" in an inline style width to 1.3 Java2D
    // pixels - CSS.LengthUnit's own mapping for "px", carrying the JDK's own
    // comment "Not sure about 1.3, determined by experimentation" - and that
    // factor applies on every machine at every screen resolution alike; it is
    // not a response to this display's DPI, and no scaling setting changes it.
    // A note declared 400 therefore always lays out at 520, which cut three
    // sentences off mid-word inside a card that had 409 to give at the
    // window's minimum size. The narrowest card the window can show is about
    // 410 screen pixels across - rail, scrollbar, column padding and card
    // border taken off the 720px minimum - and 300 lays out at 390, 17px
    // inside that. Only the JDK itself changing that 1.3 would move this
    // arithmetic, not anything this codebase controls.
    private static final int NOTE_WIDTH = 300;

    /** Java2D pixels per CSS "px" in an HTML label's inline width - see NOTE_WIDTH. */
    private static final float CSS_PX = 1.3f;

    // The type scale, from jet-swing-design-system's TYPOGRAPHY.md. Its bands
    // are application title 20 to 24, section title 15 to 16, control text
    // 13, metadata 12 and status text 11 to 12. title() keeps the 20 it
    // already had, at the band's low end; body() already sits exactly on
    // control text's 13; heading() moves from 14, which the new section
    // title band excludes, to 16, its top.
    //
    // hint() and status() stay at body()'s 13 rather than dropping to
    // metadata's 12 or status text's 11-12, and this is a read of the
    // document rather than an oversight of it. hint() is what sits beside a
    // disabled control explaining why - in the Voice section that sentence
    // is the most important line on the card, and shrinking the most
    // important line to look like a footnote works against the reason
    // metadata exists, not for it. "Status text" in TYPOGRAPHY.md names a
    // status bar's text, and this window has no status bar; status() marks
    // a state word inline in a row of body text, where it needs to be read,
    // not to recede. Both keep their hierarchy through weight and colour,
    // per TYPOGRAPHY.md's own steer to carry hierarchy with weight rather
    // than size or decorative bolding.
    public static Font title() {
        return base().deriveFont(Font.BOLD, 20f);
    }

    public static Font heading() {
        return base().deriveFont(Font.BOLD, 16f);
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
        return styled(text, title(), "text.primary");
    }

    public static JLabel heading(String text) {
        return styled(text, heading(), "text.primary");
    }

    public static JLabel body(String text) {
        return styled(text, body(), "text.primary");
    }

    /** For the sentence under a control that says what it will do. */
    public static JLabel hint(String text) {
        return styled(text, body(), "text.secondary");
    }

    /** A state word inline in a row, in whichever token names that state. */
    public static JLabel status(String text, String token) {
        return styled(text, body().deriveFont(Font.BOLD), token);
    }

    /**
     * Points a label built by this class at a different token.
     *
     * <p>For a label that is kept and written to rather than rebuilt: a state
     * word that turns from "missing" to "recorded", a report that turns from
     * muted to a warning. The label goes on resolving its colour when it
     * paints, so a theme switch after this call still reaches it. That holds
     * even for a label an earlier {@code setForeground} had stopped tracking:
     * naming a token here is a caller asking for tracking again.
     *
     * <p>A label this class did not build throws rather than falling back to
     * {@code setForeground}. The fallback would freeze the colour at whatever
     * the current mode says, which is the exact defect the token layer exists
     * to remove, and it would do so silently. An unknown token throws the same
     * way {@link #color(String)} does, before the label is touched.
     *
     * <p>A change of colour is announced as a {@code "foreground"} property
     * change, the event a label's delegate already answers by rebuilding its
     * HTML view. A wrapped label is HTML, and that view holds the foreground it
     * was built with: without the event, a sentence set first and recoloured
     * second went on painting in the old token while {@code getForeground()}
     * answered the new one.
     */
    public static void recolour(JLabel label, String token) {
        if (!(label instanceof TokenLabel tracked)) {
            throw new IllegalArgumentException("Only a label built by UiTheme can be recoloured"
                + " by token, not " + (label == null ? "null" : label.getClass().getName()));
        }
        color(token);
        Color before = tracked.getForeground();
        tracked.token = token;
        tracked.firePropertyChange("foreground", before, tracked.getForeground());
        tracked.repaint();
    }

    /**
     * Checks {@code token} before building, the same as {@link #recolour} does:
     * the label resolves its token only when it paints, and a misspelt one would
     * otherwise throw from the middle of a paint on the event thread, far from
     * the panel that named it, rather than here where the panel is built.
     */
    private static JLabel styled(String text, Font font, String token) {
        color(token);
        JLabel label = new TokenLabel(text, token);
        label.setFont(font);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * A label that resolves its colour when it paints rather than when it is
     * built.
     *
     * <p>A colour read once at construction is right until the theme changes and
     * wrong forever after, and this application learned that twice over: the
     * look and feel's own propagation reaches a component only the first time
     * its {@code updateUI} runs after attachment, so nothing corrects a cached
     * value on a later switch. Resolving in {@link #getForeground()} needs no
     * registry of every label ever made and no bookkeeping in the panels; a
     * repaint is enough.
     *
     * <p>An explicit colour from a caller still wins, and stops the tracking:
     * a renderer that paints one row red means that row red. A {@code
     * UIResource} does not, because that is the look and feel talking, not a
     * caller.
     */
    private static class TokenLabel extends JLabel {

        private String token;

        TokenLabel(String text, String token) {
            super(text);
            this.token = token;
        }

        @Override
        public Color getForeground() {
            return token == null ? super.getForeground() : color(token);
        }

        @Override
        public void setForeground(Color colour) {
            if (colour != null && !(colour instanceof javax.swing.plaf.UIResource)) {
                token = null;
            }
            super.setForeground(colour);
        }

        /** Widened so {@link #recolour} can announce a token change from outside. */
        @Override
        public void firePropertyChange(String property, Object before, Object after) {
            super.firePropertyChange(property, before, after);
        }
    }

    /**
     * The label {@link #fitted} builds. It asks for the width of its sentence on
     * one line, and when a layout gives it less, it wraps the sentence at the
     * width it was given and asks for the height that takes.
     *
     * <p>A layout decides a component's width and height in the same pass, from
     * sizes it asks for before it knows either, so the height of the wrapped
     * sentence can only be answered after the width has been handed out. The
     * label therefore re-lays its page out once more, after the pass that gave
     * it the new width has finished, the way the Status card's model row does:
     * a revalidate from inside that pass would be marked done by the pass
     * itself and lost. The second pass hands out the same width, and nothing
     * changes after it.
     */
    private static final class FittedLabel extends TokenLabel {

        // No initializers: JLabel's constructor calls setText, which sets both,
        // before this class's field initializers would run and reset them.
        private String sentence;
        // The width the markup wraps at, or 0 while the sentence is on one line.
        private int wrapWidth;
        private boolean passPending;

        FittedLabel(String text, String token) {
            super(text, token);
        }

        @Override
        public void setText(String text) {
            String plain = text == null ? "" : text;
            if (plain.equals(sentence)) {
                return;
            }
            sentence = plain;
            wrapWidth = 0;
            super.setText(plain);
        }

        /** The sentence as it reads, without the markup a wrapped one carries. */
        String sentence() {
            return sentence;
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) {
                return super.getPreferredSize();
            }
            return new Dimension(oneLine(), super.getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize() {
            if (isMinimumSizeSet()) {
                return super.getMinimumSize();
            }
            return new Dimension(Math.min(oneLine(), longestWord()), super.getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            if (width <= 0) {
                return;
            }
            int wanted = width >= oneLine() ? 0 : width;
            if (wanted == wrapWidth) {
                return;
            }
            wrapWidth = wanted;
            Insets insets = getInsets();
            super.setText(wanted == 0 ? sentence : html(sentence, wanted - insets.left - insets.right));
            if (!passPending) {
                passPending = true;
                SwingUtilities.invokeLater(() -> {
                    passPending = false;
                    revalidate();
                });
            }
        }

        private int oneLine() {
            Insets insets = getInsets();
            return getFontMetrics(getFont()).stringWidth(sentence) + insets.left + insets.right;
        }

        private int longestWord() {
            FontMetrics metrics = getFontMetrics(getFont());
            int widest = 0;
            for (String word : sentence.split("\\s+")) {
                widest = Math.max(widest, metrics.stringWidth(word));
            }
            Insets insets = getInsets();
            return widest + insets.left + insets.right;
        }
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

    /** The same wrapping, in whichever token the caller names - a warning, usually. */
    public static JLabel wrapped(String text, String token) {
        return styled(html(text), body(), token);
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
        return html(lines, NOTE_WIDTH);
    }

    /**
     * One sentence wrapped at {@code width} screen pixels rather than at a card
     * note's width, for a place narrower than a card: under the theme control, in
     * a rail 200 pixels wide.
     */
    static String html(String text, int width) {
        return html(List.of(text), (int) (width / CSS_PX));
    }

    private static String html(List<String> lines, int cssWidth) {
        StringBuilder markup = new StringBuilder("<html><body style='width:")
            .append(cssWidth).append("px'>");
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

    /**
     * A sentence that shares a row with a name or a button: one line while the
     * row has the room, and as many lines as it takes, at the width it is given,
     * when it has not.
     *
     * <p>For the sentence that says why a control is off or what to do next.
     * {@link #elastic} would ellipsise it at the window's minimum size and leave
     * the rest to a tooltip, and a sentence that names the next step is the one
     * thing on its card that has to be read whole. A {@link #wrapped} note does
     * not fit here either: it wraps at one fixed width, which beside a button is
     * either too narrow for a wide window or too wide for a narrow one.
     *
     * <p>Its minimum width is its longest word, so a row short of room takes the
     * room from this sentence before anything else, and never breaks a word.
     */
    public static JLabel fitted(String text, String token) {
        color(token);
        JLabel label = new FittedLabel(text, token);
        label.setFont(body());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    public static Border pad(int all) {
        return BorderFactory.createEmptyBorder(all, all, all, all);
    }

    /**
     * A card's 1 px outline in {@link #line()} and its {@link #SPACE} padding,
     * for a panel that looks like a card but needs a layout other than {@link
     * Card}'s grid.
     *
     * <p>The outline reads the token each time it paints. A border built once
     * holds the colour it was given, and this one used to be built from the
     * light palette's {@code border.subtle}: in the dark theme the one card
     * using it had a visibly lighter edge than every {@link Card} beside it.
     */
    public static Border card() {
        return BorderFactory.createCompoundBorder(new CardOutline(), pad(SPACE));
    }

    /**
     * Paints a one pixel outline just inside the given bounds, in the
     * graphics' current colour, the same weight on all four sides.
     *
     * <p>Four filled strips rather than {@code drawRect(x, y, width - 1,
     * height - 1)}. That call traces the edge along whole pixel coordinates,
     * and a stroke is centred on what it traces: at this machine's 2.0 scale
     * the top and left edges came out one device pixel wide and the bottom and
     * right two, with a device pixel of fill showing outside those. A strip
     * one pixel across, lying inside the bounds, covers the same device pixels
     * on every side, and at 2.0 that is the two a control's outline has.
     */
    static void outline(Graphics g, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        g.fillRect(x, y, width, 1);
        g.fillRect(x, y + height - 1, width, 1);
        g.fillRect(x, y + 1, 1, height - 2);
        g.fillRect(x + width - 1, y + 1, 1, height - 2);
    }

    /**
     * A one pixel outline in {@link #line()}, resolved when it paints and drawn
     * by {@link #outline}, the same call {@link Card} makes for its own edge.
     *
     * <p>Not a {@link javax.swing.border.LineBorder} with its colour swapped at
     * paint time. An outline meant to look like the cards beside it has to come
     * out on the same pixels as theirs at every display scale, not only in the
     * same colour, and one routine drawing both is how that holds.
     */
    private static final class CardOutline extends AbstractBorder {

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color before = g.getColor();
            g.setColor(line());
            outline(g, x, y, width, height);
            g.setColor(before);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(1, 1, 1, 1);
            return insets;
        }
    }

    /** Keeps a growable component from stretching to the height of the window. */
    public static <T extends Component> T capped(T component, int height) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return component;
    }
}
