package aura.app.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JList;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicSpinnerUI;

/**
 * The controls {@code COMPONENTS.md} and {@code INTERACTIONS.md} describe, with
 * the geometry those documents give and the states {@code INTERACTIONS.md} names:
 * normal, hover, focused, pressed and disabled, plus selected for the one control
 * that has a selection.
 *
 * <p>Ported from jet-swing-design-system's {@code
 * com.example.jetswing.theme.JetComponents} (96 lines). Kept: {@link #button},
 * {@link #primaryButton} and {@link #textField} - grepping every {@code new
 * JButton}/{@code JTextField} in {@code aura.app.ui} found none of them
 * icon-only and none inside a tool-window header. Cut: {@code panel} and {@code
 * scrollPane}, since every panel and scroll pane already in this codebase sets
 * its own background explicitly and a second way to do the one thing is not a
 * port, it is a fork; {@code toolWindow}, since the plan's Ruling 3 keeps this
 * milestone to the token layer and not jet-swing-design-system's IDE shell; and
 * {@code iconButton}, since nothing here is icon-only. Added: {@link #list} and
 * {@link RowRenderer}, which the reference does not have at all - {@code
 * JetComponents} never wraps a {@link JList}, so the row geometry, hover and
 * selection rules below are built straight from {@code COMPONENTS.md}'s "Lists
 * and trees" section, {@code INTERACTIONS.md}'s hover and focus rules, and
 * {@code DESIGN_RULES.md}'s "Selection" rule, not ported from anything. Also
 * added, and also absent from the reference: {@link #checkBox}, {@link
 * #spinner} and {@link #progressBar}, the three controls Voice setup builds
 * besides buttons. Left to the platform look and feel they paint in its colours,
 * which do not follow the theme and sit light and grey on a dark card.
 *
 * <h2>Colour, read live</h2>
 *
 * <p>Every colour here comes from {@link UiTheme} at the moment it is needed,
 * never once at construction. A component that cached a {@link Color} in its
 * constructor would look right until the theme switched and then stay wrong for
 * the rest of the run - {@code Theme}'s own class javadoc explains why:
 * {@code UIManager} plus {@code updateComponentTreeUI} only copies a colour into
 * a component the first time its {@code updateUI()} runs after the key is
 * already correct, not on every later switch. The controls here come in two
 * shapes, depending on where the colour ends up.
 *
 * <p>{@link #checkBox}, {@link #spinner} and {@link #progressBar} set no colour
 * on anything. Each paints its own surfaces and reads every token inside its
 * paint method, the same shape {@link Card} and {@code UiTheme.TokenLabel}
 * already have, so a repaint is all a theme switch needs: no listener, no handle,
 * nothing to clean up when the control goes away. The spinner has the one
 * wrinkle. The text field inside it is built by {@link JSpinner} itself and
 * cannot be subclassed, and its text is painted by its own view from colours
 * stored on it. So the spinner copies the current tokens onto that field from its
 * own {@code paintComponent}, which runs before the field paints because the
 * field is not opaque, and sets only a colour that differs from the one already
 * there, so that a paint never schedules another.
 *
 * <p>{@link #button}, {@link #primaryButton}, {@link #textField} and {@link
 * #list} store their colours on the component, as a background, a foreground or
 * a {@code Border}. They recompute those from three triggers: the state that
 * decides which colour applies (a {@link ButtonModel} change, a focus change, an
 * enabled change), and {@link Theme#onChange}, registered once per control, for
 * when the palette itself changes under it.
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

    /**
     * The check mark on a selected check box's accent fill: white, read from
     * the light palette's {@code surface.primary} rather than typed as a
     * literal, and mode-invariant for the same reason {@link #ACCENT_INK} is -
     * the fill under it does not change with the mode either.
     *
     * <p>White here and dark ink on the primary button is not a contradiction.
     * The button's label is text, and 13 px text needs 4.5:1, which white on
     * {@code accent.primary} (3.20:1) does not reach. A check mark is not text:
     * WCAG 1.4.11 asks 3:1 of a graphical object that conveys state, and 3.20:1
     * clears it. White is also what a check box's mark is expected to be, so a
     * dark one would cost recognition for contrast nobody needed.
     */
    private static final Color CHECK_MARK = UiTheme.LIGHT_PALETTE.get("surface.primary");

    /** The side of the check box's square. */
    private static final int CHECK_SIZE = 16;

    private static final int CHECK_RADIUS = UiTheme.radius(4);

    /**
     * Room kept around the check box's square for its focus ring. The ring sits
     * outside the square rather than recolouring its outline, because a selected
     * square is filled with {@code accent.primary} and the dark palette's {@code
     * border.focus} is the very same hex: an outline drawn on the fill would be
     * invisible exactly when it is needed.
     */
    private static final int CHECK_RING = 2;

    /** The spinner's field corners, matching the check box's rather than the buttons' 6. */
    private static final int SPINNER_RADIUS = UiTheme.radius(4);

    /** Each spinner arrow's width: room for a 7 px chevron and a hover chip around it. */
    private static final int ARROW_WIDTH = 18;

    private static final int PROGRESS_RADIUS = UiTheme.radius(4);

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
     * A check box whose square is painted here: {@value #CHECK_SIZE} px, radius
     * 4, a 1 px {@code border.default} outline on {@code surface.input} when
     * clear, and filled with {@code accent.primary} under a white mark when
     * selected. Focus shows as a {@code border.focus} ring just outside the
     * square, in both states - see {@link #CHECK_RING} for why outside. The
     * label is {@code text.primary}.
     *
     * <p>Disabled follows {@link #button}'s lead: the surface stays as it is and
     * the foreground and outline recede. The label turns {@code text.disabled},
     * the outline {@code border.subtle}, and a selected square keeps the input
     * surface with its mark drawn in {@code text.disabled} instead of the
     * accent fill, so "on but not changeable right now" still reads as on.
     *
     * <p>Only painting changes. Space toggles it and a click toggles it, both
     * through the platform's own {@link JCheckBox} handling, which this class
     * leaves in place.
     */
    public static JCheckBox checkBox(String text) {
        return new CheckBox(text);
    }

    /**
     * A {@value #ARROW_WIDTH} px wide pair of quiet arrows beside a number
     * field: {@link UiTheme#controlHeight()} high, the field on {@code
     * surface.input} inside a 1 px {@code border.default} outline at radius 4,
     * the outline turning {@code border.focus} while the field has keyboard
     * focus, the digits in {@code text.primary}. The arrows have no bevel: a
     * chevron in {@code text.secondary}, and a {@code surface.secondary} chip
     * behind it on hover.
     *
     * <p>As wide as the model's longest value needs and no wider, and it keeps
     * that width in a layout that offers more: {@link JSpinner.NumberEditor}
     * already sizes its field to the longer of the model's minimum and maximum,
     * and a spinner told to stretch to the end of a row stops looking like a
     * place for two digits.
     */
    public static JSpinner spinner(SpinnerNumberModel model) {
        return new Spinner(model);
    }

    /**
     * A flat bar: a {@code surface.secondary} track, an {@code accent.primary}
     * fill, radius 4, no bevel, and a centred caption when the caller turns one
     * on with {@link JProgressBar#setStringPainted}.
     *
     * <p>The caption is drawn twice, each time clipped to one side of the fill's
     * edge: over the fill in {@link #ACCENT_INK}, the dark ink that reaches
     * 5.04:1 on the accent in both themes, and over the track in {@code
     * text.primary}, which reaches far more than that on {@code
     * surface.secondary} in either theme. A single ink cannot do both jobs in
     * the dark theme, where the track wants light text and the fill wants dark:
     * the dark palette's {@code text.primary} on the accent is 2.62:1, and dark
     * ink on the dark track is close to invisible. A caption moved off the bar
     * would dodge the question, but it would also move the one line that says
     * what the bar is measuring away from the bar measuring it.
     *
     * <p>Horizontal and determinate only. An indeterminate bar paints its track
     * and caption with no moving part: the look and feel's animator repaints
     * only the region of its own bouncing box, which a painter of the whole bar
     * would tear, and nothing in this application sets one indeterminate.
     */
    public static JProgressBar progressBar() {
        return new ProgressBar();
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

    private static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /**
     * A check box that paints its own square and label - see {@link #checkBox}.
     *
     * <p>Package-private, with the colours it paints exposed as methods, so a
     * test can ask what a state looks like without rasterising it. The look and
     * feel's delegate stays installed for what it does besides painting: the
     * Space binding, the mouse handling, the preferred size.
     */
    static final class CheckBox extends JCheckBox {

        private boolean focused;

        CheckBox(String text) {
            super(text);
            setOpaque(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setFont(UiTheme.body());
            setIcon(new CheckIcon());
            setIconTextGap(UiTheme.space(8));
            // Not a UIResource, so the look and feel's own border, with its
            // platform margin, is not put back on the next updateUI.
            setBorder(BorderFactory.createEmptyBorder());
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    focused = true;
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    focused = false;
                    repaint();
                }
            });
        }

        /**
         * The label's colour, resolved on every call. Overridden rather than
         * set so that the look and feel, which installs a foreground only over
         * a missing one or its own, never replaces it either.
         */
        @Override
        public Color getForeground() {
            return UiTheme.color(isEnabled() ? "text.primary" : "text.disabled");
        }

        boolean focused() {
            return focused;
        }

        Color boxFill() {
            ButtonModel model = getModel();
            boolean enabled = isEnabled();
            if (enabled && model.isSelected()) {
                if (model.isArmed() && model.isPressed()) {
                    return UiTheme.color("accent.pressed");
                }
                return model.isRollover() ? UiTheme.color("accent.hover") : UiTheme.accent();
            }
            boolean hover = enabled && (model.isRollover() || model.isPressed());
            return UiTheme.color(hover ? "surface.secondary" : "surface.input");
        }

        /** The square's own outline, or null where its fill is the edge. */
        Color boxOutline() {
            if (!isEnabled()) {
                return UiTheme.color("border.subtle");
            }
            return getModel().isSelected() ? null : UiTheme.color("border.default");
        }

        Color markColour() {
            return isEnabled() ? CHECK_MARK : UiTheme.color("text.disabled");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                Insets insets = getInsets();
                Rectangle view = new Rectangle(insets.left, insets.top,
                    getWidth() - insets.left - insets.right,
                    getHeight() - insets.top - insets.bottom);
                Rectangle iconBounds = new Rectangle();
                Rectangle textBounds = new Rectangle();
                FontMetrics fm = getFontMetrics(getFont());
                String text = getText();
                String shown = SwingUtilities.layoutCompoundLabel(this, fm, text, getIcon(),
                    getVerticalAlignment(), getHorizontalAlignment(),
                    getVerticalTextPosition(), getHorizontalTextPosition(),
                    view, iconBounds, textBounds,
                    text == null || text.isEmpty() ? 0 : getIconTextGap());
                if (getIcon() != null) {
                    getIcon().paintIcon(this, g2, iconBounds.x, iconBounds.y);
                }
                if (shown != null && !shown.isEmpty()) {
                    g2.setFont(getFont());
                    g2.setColor(getForeground());
                    BasicGraphicsUtils.drawString(this, g2, shown, textBounds.x,
                        textBounds.y + fm.getAscent());
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** The square, the mark and the focus ring {@link CheckBox} paints, read at paint time. */
    private static final class CheckIcon implements Icon {

        @Override
        public int getIconWidth() {
            return CHECK_SIZE + 2 * CHECK_RING;
        }

        @Override
        public int getIconHeight() {
            return CHECK_SIZE + 2 * CHECK_RING;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (!(c instanceof CheckBox box)) {
                return;
            }
            Graphics2D g2 = smooth(g);
            try {
                int left = x + CHECK_RING;
                int top = y + CHECK_RING;
                int arc = CHECK_RADIUS * 2;
                g2.setColor(box.boxFill());
                g2.fillRoundRect(left, top, CHECK_SIZE, CHECK_SIZE, arc, arc);
                Color outline = box.boxOutline();
                if (outline != null) {
                    g2.setColor(outline);
                    g2.drawRoundRect(left, top, CHECK_SIZE - 1, CHECK_SIZE - 1, arc, arc);
                }
                if (box.isSelected()) {
                    Path2D.Float mark = new Path2D.Float();
                    mark.moveTo(left + 4f, top + 8.5f);
                    mark.lineTo(left + 7f, top + 11.5f);
                    mark.lineTo(left + 12f, top + 5f);
                    g2.setColor(box.markColour());
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(mark);
                }
                if (box.focused() && box.isEnabled()) {
                    g2.setColor(UiTheme.color("border.focus"));
                    g2.setStroke(new BasicStroke(1f));
                    int ringArc = (CHECK_RADIUS + CHECK_RING) * 2;
                    g2.drawRoundRect(x, y, getIconWidth() - 1, getIconHeight() - 1, ringArc, ringArc);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** A number spinner painted here - see {@link #spinner}. */
    static final class Spinner extends JSpinner {

        private boolean focused;

        // A field initialiser, so it exists only once super() has returned:
        // JSpinner's constructor builds the editor and installs the delegate
        // before any of this class's own state is there to be read.
        private final FocusListener editorFocus = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                focused = true;
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                focused = false;
                repaint();
            }
        };

        Spinner(SpinnerNumberModel model) {
            super(model);
            setOpaque(false);
            setFont(UiTheme.body());
            // One pixel all round, so the editor and the arrows sit inside the
            // outline paintComponent draws rather than over it.
            setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
            addPropertyChangeListener("editor", e -> styleEditor());
            styleEditor();
        }

        /**
         * Always this class's own delegate, including on the {@code
         * updateComponentTreeUI} every theme switch runs. A platform delegate
         * would bring back its bevelled arrow buttons.
         */
        @Override
        public void updateUI() {
            setUI(new SpinnerSkin());
        }

        boolean focused() {
            return focused;
        }

        Color outline() {
            if (!isEnabled()) {
                return UiTheme.color("border.subtle");
            }
            return UiTheme.color(focused ? "border.focus" : "border.default");
        }

        JFormattedTextField field() {
            return getEditor() instanceof DefaultEditor editor ? editor.getTextField() : null;
        }

        private void styleEditor() {
            JComponent editor = getEditor();
            editor.setOpaque(false);
            JFormattedTextField field = field();
            if (field == null) {
                return;
            }
            field.setOpaque(false);
            field.setFont(UiTheme.body());
            field.setBorder(BorderFactory.createEmptyBorder(0, UiTheme.space(8), 0, UiTheme.space(4)));
            field.removeFocusListener(editorFocus);
            field.addFocusListener(editorFocus);
            syncField(field);
        }

        /** Copies the current tokens onto the field, touching only what changed. */
        private void syncField(JFormattedTextField field) {
            Color text = UiTheme.color("text.primary");
            if (!text.equals(field.getForeground())) {
                field.setForeground(text);
            }
            if (!text.equals(field.getSelectedTextColor())) {
                field.setSelectedTextColor(text);
            }
            Color disabled = UiTheme.color("text.disabled");
            if (!disabled.equals(field.getDisabledTextColor())) {
                field.setDisabledTextColor(disabled);
            }
            Color caret = UiTheme.accent();
            if (!caret.equals(field.getCaretColor())) {
                field.setCaretColor(caret);
            }
            Color selection = UiTheme.color("surface.selection");
            if (!selection.equals(field.getSelectionColor())) {
                field.setSelectionColor(selection);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, UiTheme.controlHeight());
        }

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
            JFormattedTextField field = field();
            if (field != null) {
                syncField(field);
            }
            Graphics2D g2 = smooth(g);
            try {
                int arc = SPINNER_RADIUS * 2;
                g2.setColor(UiTheme.color("surface.input"));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(outline());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * The delegate {@link Spinner} installs: the basic one, with this class's
     * arrows instead of the platform's. Reads nothing from its spinner beyond
     * what {@link BasicSpinnerUI} already does, because it is installed from
     * inside {@link JSpinner}'s constructor, before {@link Spinner} has any
     * state of its own.
     */
    private static final class SpinnerSkin extends BasicSpinnerUI {

        @Override
        protected Component createNextButton() {
            ArrowButton button = new ArrowButton(SwingConstants.NORTH);
            button.setName("Spinner.nextButton");
            installNextButtonListeners(button);
            return button;
        }

        @Override
        protected Component createPreviousButton() {
            ArrowButton button = new ArrowButton(SwingConstants.SOUTH);
            button.setName("Spinner.previousButton");
            installPreviousButtonListeners(button);
            return button;
        }

        /**
         * Digits against the arrows whatever the look and feel prefers, so the
         * number sits in the same place under every delegate that has ever
         * been installed.
         */
        @Override
        protected JComponent createEditor() {
            JComponent editor = super.createEditor();
            if (editor instanceof JSpinner.DefaultEditor text) {
                text.getTextField().setHorizontalAlignment(SwingConstants.TRAILING);
            }
            return editor;
        }
    }

    /** One of a spinner's two arrows: a chevron, and a chip behind it on hover. */
    private static final class ArrowButton extends JButton {

        private final int direction;

        ArrowButton(int direction) {
            this.direction = direction;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder());
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(ARROW_WIDTH, UiTheme.controlHeight() / 2);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                ButtonModel model = getModel();
                boolean enabled = isEnabled();
                if (enabled && (model.isRollover() || model.isPressed())) {
                    boolean pressed = model.isArmed() && model.isPressed();
                    g2.setColor(UiTheme.color(pressed ? "border.subtle" : "surface.secondary"));
                    int arc = UiTheme.radius(4) * 2;
                    // Inset by a pixel so the chip never reaches the rounded
                    // corner of the outline the spinner draws around it.
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
                }
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float half = 3.5f;
                float rise = direction == SwingConstants.NORTH ? -1.75f : 1.75f;
                Path2D.Float chevron = new Path2D.Float();
                chevron.moveTo(cx - half, cy - rise);
                chevron.lineTo(cx, cy + rise);
                chevron.lineTo(cx + half, cy - rise);
                g2.setColor(UiTheme.color(enabled ? "text.secondary" : "text.disabled"));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(chevron);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A flat progress bar with a two-ink caption - see {@link #progressBar}. */
    static final class ProgressBar extends JProgressBar {

        ProgressBar() {
            setOpaque(false);
            setBorderPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setFont(UiTheme.body());
        }

        /** The caption's colour where it crosses the fill. */
        Color inkOverFill() {
            return isEnabled() ? ACCENT_INK : UiTheme.color("text.disabled");
        }

        /** The caption's colour where it lies on the bare track. */
        Color inkOverTrack() {
            return UiTheme.color(isEnabled() ? "text.primary" : "text.disabled");
        }

        Color track() {
            return UiTheme.color("surface.secondary");
        }

        Color fill() {
            return isEnabled() ? UiTheme.accent() : UiTheme.color("border.default");
        }

        /** Tall enough for one line of caption with a 4 px band above and below. */
        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(super.getPreferredSize().width,
                fm.getHeight() + 2 * UiTheme.space(4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            try {
                int width = getWidth();
                int height = getHeight();
                int arc = PROGRESS_RADIUS * 2;
                g2.setColor(track());
                g2.fillRoundRect(0, 0, width, height, arc, arc);

                int filled = isIndeterminate() ? 0
                    : (int) Math.round(width * Math.max(0, Math.min(1, getPercentComplete())));
                Shape whole = g2.getClip();
                if (filled > 0) {
                    // The fill is the whole rounded bar clipped at its leading
                    // edge, so it keeps the track's left corners and meets the
                    // rest of the track with a straight cut.
                    g2.clipRect(0, 0, filled, height);
                    g2.setColor(fill());
                    g2.fillRoundRect(0, 0, width, height, arc, arc);
                    g2.setClip(whole);
                }

                String caption = isStringPainted() ? getString() : null;
                if (caption == null || caption.isEmpty()) {
                    return;
                }
                g2.setFont(getFont());
                FontMetrics fm = getFontMetrics(getFont());
                int x = Math.max(UiTheme.space(8), (width - fm.stringWidth(caption)) / 2);
                int y = (height - fm.getHeight()) / 2 + fm.getAscent();

                g2.clipRect(0, 0, filled, height);
                g2.setColor(inkOverFill());
                BasicGraphicsUtils.drawString(this, g2, caption, x, y);
                g2.setClip(whole);

                g2.clipRect(filled, 0, width - filled, height);
                g2.setColor(inkOverTrack());
                BasicGraphicsUtils.drawString(this, g2, caption, x, y);
                g2.setClip(whole);
            } finally {
                g2.dispose();
            }
        }
    }
}
