package aura.app.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.border.Border;

/**
 * The controls {@code COMPONENTS.md} and {@code INTERACTIONS.md} describe, with
 * the geometry those documents give and the states {@code INTERACTIONS.md} names:
 * normal, hover, focused, pressed and disabled, plus selected for the one control
 * that has a selection.
 *
 * <p>Ported from jet-swing-design-system's {@code
 * com.example.jetswing.theme.JetComponents} (96 lines). Kept: {@link #button},
 * {@link #primaryButton} and {@link #textField}, the three controls this
 * application actually builds - grepping every {@code new JButton}/{@code
 * JTextField} in {@code aura.app.ui} found none of them icon-only and none
 * inside a tool-window header. Cut: {@code panel} and {@code scrollPane}, since
 * every panel and scroll pane already in this codebase sets its own background
 * explicitly and a second way to do the one thing is not a port, it is a fork;
 * {@code toolWindow}, since the plan's Ruling 3 keeps this milestone to the
 * token layer and not jet-swing-design-system's IDE shell; and {@code
 * iconButton}, since nothing here is icon-only. Added: {@link #list} and {@link
 * RowRenderer}, which the reference does not have at all - {@code
 * JetComponents} never wraps a {@link JList}, so the row geometry, hover and
 * selection rules below are built straight from {@code COMPONENTS.md}'s "Lists
 * and trees" section, {@code INTERACTIONS.md}'s hover and focus rules, and
 * {@code DESIGN_RULES.md}'s "Selection" rule, not ported from anything.
 *
 * <h2>Colour, read live</h2>
 *
 * <p>Every colour here comes from {@link UiTheme} at the moment it is needed,
 * never once at construction. A component that cached a {@link Color} in its
 * constructor would look right until the theme switched and then stay wrong for
 * the rest of the run - {@code Theme}'s own class javadoc explains why:
 * {@code UIManager} plus {@code updateComponentTreeUI} only copies a colour into
 * a component the first time its {@code updateUI()} runs after the key is
 * already correct, not on every later switch. Every control built here instead
 * recomputes its own colours from three triggers: the state that decides which
 * colour applies (a {@link ButtonModel} change, a focus change, an enabled
 * change), and {@link Theme#onChange}, registered once per control, for when the
 * palette itself changes under it.
 *
 * <p>{@link Theme#onChange} returns a handle for exactly this reason: a factory
 * that registers a listener per control would otherwise leak one every time a
 * caller rebuilds rather than mutates - the dead control would stay reachable
 * from {@code Theme}'s listener list and its listener would keep running on
 * every later switch, holding the discarded control in memory to do it.
 * {@link #button}, {@link #primaryButton}, {@link #textField} and {@link #list}
 * all keep the handle and run it the moment the control they built leaves a
 * displayable hierarchy - {@link Component#removeNotify()} for the three this
 * class defines, and the equivalent {@link HierarchyListener} for {@link
 * #list}, since a caller supplies the {@link JList} itself and this class has
 * no subclass of it to override {@code removeNotify} on. Each also
 * re-subscribes if the same control is shown again by way of {@link
 * Component#addNotify()} - {@code AuraWindow}
 * disposes its frame on close and realises the same component tree again on
 * reopen, and a control that stayed unsubscribed through that would silently
 * stop tracking the palette for the rest of the run rather than merely for the
 * time it was hidden.
 *
 * <h2>The primary button's ink is deliberately not white</h2>
 *
 * <p>{@code JetComponents.primaryButton} paints white text on {@code
 * accent.primary}. Measured against this project's actual token values (WCAG
 * relative luminance, the same formula {@code contrast-ratio} tools use), white
 * reaches only 3.20:1 on the resting fill and 2.69:1 on {@code accent.hover} -
 * both under the 3:1 floor WCAG sets even for large or bold UI text. The light
 * palette's own {@code text.primary} (#202124) reaches 5.04:1 on the resting
 * fill and 5.99:1 on hover, because {@code accent.primary}'s luminance (0.28)
 * sits above the crossover point (about 0.18) past which a dark ink out-contrasts
 * a light one against a fixed background. {@link #ACCENT_INK} reads {@code
 * text.primary} out of {@link UiTheme#LIGHT_PALETTE} directly rather than
 * through {@link UiTheme#color}, on purpose and unconditionally: {@code
 * accent.primary}, {@code accent.hover} and {@code accent.pressed} are the same
 * hex in both palettes (TOKENS.md gives them no dark/light split), so the ink
 * that reads against them cannot be mode-dependent either without going
 * unreadable in one mode - the exact shape {@link UiTheme#INK} already is a
 * precedent for, a deliberately mode-invariant lookup into one named palette.
 * This is not the theme-staleness bug the class comment above warns about:
 * that bug is caching a colour that is supposed to change with the mode and
 * does not; {@link #ACCENT_INK} is supposed to never change with the mode, the
 * same way the accent tokens it sits on already do not. TOKENS.md defines no
 * {@code text.onAccent} token to read instead, and inventing one would fail
 * {@code UiThemeTest}'s own token-agreement test, which lists TOKENS.md's
 * twenty-two names exactly.
 *
 * <h2>Hover, lighter than selection</h2>
 *
 * <p>{@code INTERACTIONS.md} asks hover to be "visually lighter than
 * selection"; {@code COMPONENTS.md} says the same thing in different words -
 * "Hover is subtle. Selection is stronger and persistent." Read as literal
 * luminance this rule cannot hold in both themes at once: dark's {@code
 * surface.selection} (#34415A, L=0.053) is lighter than dark's own list
 * background (#1E1F22, L=0.014), while light's {@code surface.selection}
 * (#DDE8FF, L=0.803) is darker than light's own background (#FFFFFF, L=1.0).
 * A rule that would require hover to sit on opposite sides of selection in the
 * two themes is not a rule a fixed pair of colours can satisfy, so {@link
 * #rowBackground} reads "lighter" as "subtler": hover is {@link #mix}ed toward
 * {@code surface.selection} at {@link #ROW_HOVER_MIX} of the way there, and
 * selection is the full tint. Whichever direction that tint moves luminance in
 * a given theme, hover moves the same direction by less - which is what
 * {@code JetControlsTest} pins, as a distance from the base surface in both
 * themes, rather than as either colour's own value.
 *
 * <h2>Selection, not by colour alone</h2>
 *
 * <p>{@code DESIGN_RULES.md} and {@code ACCESSIBILITY.md} both ask for a second
 * signal besides the tint. {@link #rowBorder} adds a stripe: a {@link
 * javax.swing.border.MatteBorder} in {@link UiTheme#accent()} on the selected
 * row's leading edge, and empty space of the identical width on every other
 * row, so a row's total left inset never changes and selecting one never shifts
 * its label sideways. The stripe's presence, not its colour, is what a reviewer
 * without colour vision reads.
 */
public final class JetControls {

    private JetControls() { }

    /** {@code COMPONENTS.md}: "radius 6 px" for buttons. */
    private static final int BUTTON_RADIUS = UiTheme.radius(6);

    /**
     * {@code COMPONENTS.md} gives list rows 28 to 32 px; 32 is picked so a row
     * sits on the same rhythm as {@link UiTheme#controlHeight()}, which every
     * button and field on the same screen already uses.
     */
    private static final int ROW_HEIGHT = UiTheme.controlHeight();

    /**
     * Width of the selection stripe {@link #rowBorder} paints. Not itself a
     * TOKENS.md value - the document has no stripe-width token - kept narrow
     * enough to read as an accent mark rather than a second selection fill.
     */
    private static final int ROW_STRIPE = 3;

    /**
     * How far {@link #mix} pulls a hovered row's background toward {@code
     * surface.selection}, against the full 1.0 selection itself sits at.
     * Chosen by rendering both themes at this machine's own 2.0 scale and
     * reading the result, per the milestone's own rule that a state is a thing
     * a picture settles: below this a light-theme hover was indistinguishable
     * from no hover at all.
     */
    private static final double ROW_HOVER_MIX = 0.55;

    private static final String HOVER_PROPERTY = "jet.hoveredRow";
    private static final String FOCUS_PROPERTY = "jet.focused";

    /** The painted stroke colour of a control that draws its own border. */
    static final String BORDER_COLOUR_PROPERTY = "jet.borderColour";

    /**
     * The primary button's own label colour. See the class javadoc's "primary
     * button's ink" section for why this is a fixed lookup into one palette
     * rather than {@link UiTheme#color} or a literal.
     */
    private static final Color ACCENT_INK = UiTheme.LIGHT_PALETTE.get("text.primary");

    /** A neutral button: {@code COMPONENTS.md}'s "Secondary buttons are neutral." */
    public static JButton button(String text) {
        return new Button(text, false);
    }

    /**
     * The one accent-filled action of a local context - {@code COMPONENTS.md}'s
     * "Primary buttons are reserved for the major action."
     */
    public static JButton primaryButton(String text) {
        return new Button(text, true);
    }

    /**
     * A 32 px field with an 8 px horizontal border padding (inside {@code
     * COMPONENTS.md}'s 8-10 px), a 1 px border that turns {@code border.focus}
     * on focus, and a placeholder painted in {@code text.tertiary} while the
     * field is empty. Square rather than radiused: unlike buttons, the task
     * this class was built for names no radius for fields, and {@link
     * UiTheme#radius(int)} accepts 0 as one of TOKENS.md's own documented
     * values rather than refusing it.
     */
    public static JTextField textField(String placeholder) {
        return new PlaceholderField(placeholder);
    }

    /**
     * Fits {@code target} for {@code COMPONENTS.md}'s list rules: a fixed
     * {@value #ROW_HEIGHT} px row height, hover tracked by mouse position, and
     * a border that turns {@code border.focus} while the list itself holds
     * keyboard focus - the one indicator that still shows when nothing in the
     * list is selected yet. A caller wrapping the result in a {@code
     * JScrollPane} should leave that scroll pane's own border null, the same
     * way every whole-column scroll pane in this codebase already does, so
     * this border is the one that shows.
     *
     * <p>Returns {@code target} so a caller can chain it the way {@link
     * UiTheme#capped} already does.
     */
    public static <T> JList<T> list(JList<T> target) {
        target.setFixedCellHeight(ROW_HEIGHT);
        MouseAdapter hover = hoverTracker(target);
        target.addMouseListener(hover);
        target.addMouseMotionListener(hover);
        target.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                target.putClientProperty(FOCUS_PROPERTY, Boolean.TRUE);
                refreshListChrome(target);
            }

            @Override
            public void focusLost(FocusEvent e) {
                target.putClientProperty(FOCUS_PROPERTY, Boolean.FALSE);
                refreshListChrome(target);
            }
        });
        target.addHierarchyListener(new ThemeTracker(target));
        refreshListChrome(target);
        return target;
    }

    /**
     * Keeps a {@link JList} passed through {@link #list} subscribed to {@link
     * Theme#onChange} only while it is displayable. {@link
     * Component#removeNotify()} is not an option here - {@code target} is
     * the caller's own instance, not a class this file defines - so this
     * reacts to the same underlying transition {@code removeNotify}
     * and {@code addNotify} are built on, {@link
     * HierarchyEvent#DISPLAYABILITY_CHANGED}, which fires in exactly the same
     * cases: never for a list that is only ever constructed and not shown,
     * and on both directions of a real show/hide.
     */
    private static final class ThemeTracker implements HierarchyListener {

        private final JList<?> target;
        private Runnable unsubscribe;

        ThemeTracker(JList<?> target) {
            this.target = target;
            this.unsubscribe = Theme.onChange(() -> refreshListChrome(target));
        }

        @Override
        public void hierarchyChanged(HierarchyEvent e) {
            if ((e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            if (target.isDisplayable()) {
                if (unsubscribe == null) {
                    unsubscribe = Theme.onChange(() -> refreshListChrome(target));
                    refreshListChrome(target);
                }
            } else if (unsubscribe != null) {
                unsubscribe.run();
                unsubscribe = null;
            }
        }
    }

    private static void refreshListChrome(JList<?> list) {
        list.setBackground(UiTheme.surface());
        // Set here as well as in RowRenderer, because a list whose caller has
        // not installed one still has to be legible: without it the rows keep
        // the look and feel's own foreground against a themed background, which
        // in the dark palette is near-black on near-black.
        list.setForeground(UiTheme.color("text.primary"));
        Object focused = list.getClientProperty(FOCUS_PROPERTY);
        Color line = Boolean.TRUE.equals(focused)
            ? UiTheme.color("border.focus") : UiTheme.color("border.subtle");
        list.setBorder(BorderFactory.createLineBorder(line));
        list.repaint();
    }

    private static MouseAdapter hoverTracker(JList<?> list) {
        return new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setHover(indexAt(e.getPoint()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(-1);
            }

            private int indexAt(Point point) {
                int index = list.locationToIndex(point);
                if (index < 0) {
                    return -1;
                }
                Rectangle bounds = list.getCellBounds(index, index);
                return bounds != null && bounds.contains(point) ? index : -1;
            }

            private void setHover(int index) {
                if (hoveredIndex(list) == index) {
                    return;
                }
                list.putClientProperty(HOVER_PROPERTY, index);
                list.repaint();
            }
        };
    }

    static int hoveredIndex(JList<?> list) {
        Object value = list.getClientProperty(HOVER_PROPERTY);
        return value instanceof Integer i ? i : -1;
    }

    /**
     * A row's fill: the full {@code surface.selection} tint when selected, a
     * partial {@link #mix} of it when hovered, and the plain surface otherwise.
     * See the class javadoc's "Hover, lighter than selection" section for why
     * this is a distance from the base surface rather than a fixed colour.
     */
    static Color rowBackground(boolean selected, boolean hovered) {
        if (selected) {
            return UiTheme.color("surface.selection");
        }
        if (hovered) {
            return mix(UiTheme.surface(), UiTheme.color("surface.selection"), ROW_HOVER_MIX);
        }
        return UiTheme.surface();
    }

    /**
     * A row's border: an accent stripe on the leading edge when selected, empty
     * space of the same width otherwise, both compounded with the same padding
     * - so the total inset never changes and only the stripe's presence does.
     * See the class javadoc's "Selection, not by colour alone" section.
     */
    static Border rowBorder(boolean selected) {
        int pad = UiTheme.space(8);
        Border padding = BorderFactory.createEmptyBorder(0, pad, 0, pad);
        Border leading = selected
            ? BorderFactory.createMatteBorder(0, ROW_STRIPE, 0, 0, UiTheme.accent())
            : BorderFactory.createEmptyBorder(0, ROW_STRIPE, 0, 0);
        return BorderFactory.createCompoundBorder(leading, padding);
    }

    /**
     * Linear interpolation between two {@link UiTheme} colours. Not a literal
     * colour itself - both endpoints already came from {@link UiTheme}, and
     * this only computes a point between them - the same way a gradient
     * computed between two theme colours would not itself be a third one
     * typed into this file.
     */
    static Color mix(Color base, Color toward, double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        int r = (int) Math.round(base.getRed() + (toward.getRed() - base.getRed()) * f);
        int g = (int) Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * f);
        int b = (int) Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * f);
        return new Color(r, g, b);
    }

    /**
     * A {@link javax.swing.ListCellRenderer} that gives every row {@code
     * COMPONENTS.md}'s geometry and hover/selection rules; a caller supplies
     * only how a value of type {@code T} becomes the row's label. Meant for a
     * {@link JList} already passed through {@link #list}, so the hover index
     * {@link #rowBackground} reads is the one {@link #list} keeps current.
     */
    public abstract static class RowRenderer<T> extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            @SuppressWarnings("unchecked")
            T typed = (T) value;
            super.getListCellRendererComponent(list, text(typed), index, isSelected, cellHasFocus);
            boolean hovered = !isSelected && index == hoveredIndex(list);
            setOpaque(true);
            setBackground(rowBackground(isSelected, hovered));
            setForeground(list.isEnabled()
                ? UiTheme.color("text.primary") : UiTheme.color("text.disabled"));
            setBorder(rowBorder(isSelected));
            return this;
        }

        /** The row's own label - a voice's blind name, a section's title, whatever {@code T} is. */
        protected abstract String text(T value);
    }

    /**
     * Both button kinds behind one class: same geometry and the same
     * hover/pressed/focus/disabled state machine, differing only in which
     * colours each state maps to. Paints its own rounded fill and outline
     * because a plain {@link JButton} under this project's plain look and feel
     * (Ruling 1: no FlatLaf) has no rounded corners to ask for.
     */
    private static final class Button extends JButton {

        private final boolean primary;
        private boolean focused;
        private Color borderColor;
        private Runnable unsubscribeTheme;

        Button(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setFont(UiTheme.body());
            setBorder(BorderFactory.createEmptyBorder(0, UiTheme.space(12), 0, UiTheme.space(12)));
            getModel().addChangeListener(e -> refresh());
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    focused = true;
                    refresh();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    focused = false;
                    refresh();
                }
            });
            unsubscribeTheme = Theme.onChange(this::refresh);
            refresh();
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (unsubscribeTheme == null) {
                unsubscribeTheme = Theme.onChange(this::refresh);
                refresh();
            }
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            if (unsubscribeTheme != null) {
                unsubscribeTheme.run();
                unsubscribeTheme = null;
            }
        }

        private void refresh() {
            ButtonModel model = getModel();
            boolean enabled = isEnabled();
            boolean pressed = enabled && model.isArmed() && model.isPressed();
            boolean hover = enabled && model.isRollover();
            setBackground(backgroundFor(enabled, hover, pressed));
            setForeground(enabled
                ? (primary ? ACCENT_INK : UiTheme.color("text.primary"))
                : UiTheme.color("text.disabled"));
            borderColor = borderColorFor(enabled, pressed);
            // A rounded border at TOKENS.md's 6 px radius has to be painted
            // rather than set, so getBorder() carries only the padding and the
            // stroke colour would otherwise be unreadable from outside.
            putClientProperty(BORDER_COLOUR_PROPERTY, borderColor);
            repaint();
        }

        private Color backgroundFor(boolean enabled, boolean hover, boolean pressed) {
            if (!enabled) {
                return UiTheme.color("surface.raised");
            }
            if (primary) {
                if (pressed) {
                    return UiTheme.color("accent.pressed");
                }
                return hover ? UiTheme.color("accent.hover") : UiTheme.accent();
            }
            return (pressed || hover) ? UiTheme.color("surface.secondary") : UiTheme.color("surface.raised");
        }

        private Color borderColorFor(boolean enabled, boolean pressed) {
            if (!enabled) {
                return UiTheme.color("border.subtle");
            }
            if (pressed) {
                return UiTheme.accent();
            }
            if (focused) {
                return UiTheme.color("border.focus");
            }
            return primary ? null : UiTheme.color("border.default");
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension natural = super.getPreferredSize();
            return new Dimension(natural.width, UiTheme.controlHeight());
        }

        /**
         * Pinned to the preferred size. The delegate answers its own natural
         * size here, which knows nothing of the height above, and a {@code
         * BoxLayout} row never makes a component taller than its maximum: every
         * button in one came out the height of its text, 24 px beside a 32 px
         * field or spinner in the same row.
         */
        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = BUTTON_RADIUS * 2;
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                if (borderColor != null) {
                    g2.setColor(borderColor);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** A 32 px text field with a placeholder painted by hand - see {@link #textField}. */
    private static final class PlaceholderField extends JTextField {

        private final String placeholder;
        private boolean focused;
        private Runnable unsubscribeTheme;

        PlaceholderField(String placeholder) {
            this.placeholder = Objects.requireNonNull(placeholder, "placeholder");
            setFont(UiTheme.body());
            setPreferredSize(new Dimension(240, UiTheme.controlHeight()));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    focused = true;
                    refresh();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    focused = false;
                    refresh();
                }
            });
            unsubscribeTheme = Theme.onChange(this::refresh);
            refresh();
        }

        String placeholder() {
            return placeholder;
        }

        @Override
        public void addNotify() {
            super.addNotify();
            if (unsubscribeTheme == null) {
                unsubscribeTheme = Theme.onChange(this::refresh);
                refresh();
            }
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            if (unsubscribeTheme != null) {
                unsubscribeTheme.run();
                unsubscribeTheme = null;
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            refresh();
        }

        private void refresh() {
            setBackground(UiTheme.color("surface.input"));
            setForeground(isEnabled() ? UiTheme.color("text.primary") : UiTheme.color("text.disabled"));
            setCaretColor(UiTheme.accent());
            Color line = !isEnabled() ? UiTheme.color("border.subtle")
                : focused ? UiTheme.color("border.focus") : UiTheme.color("border.default");
            int pad = UiTheme.space(8);
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(line), BorderFactory.createEmptyBorder(5, pad, 5, pad)));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (placeholder.isEmpty() || !getText().isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UiTheme.color("text.tertiary"));
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = getInsets().left;
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(placeholder, x, y);
            } finally {
                g2.dispose();
            }
        }
    }
}
