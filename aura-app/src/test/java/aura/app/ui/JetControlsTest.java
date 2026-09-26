package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the ported controls promise beyond "it compiles": the geometry {@code
 * COMPONENTS.md} gives each one, and the three interaction rules the task this
 * class was written for calls out by name. Each test's comment names the
 * production change that would turn it red, the same convention {@code
 * UiThemeTest} uses and for the same reason - a test that only restates the
 * implementation catches nothing a typo could not also produce by accident.
 *
 * <p>Focus is asserted through a synthetic {@link FocusEvent} dispatched
 * straight at the component rather than through {@code requestFocusInWindow()}:
 * none of these components are ever realized in a window here, so the real
 * {@code KeyboardFocusManager} never grants them anything to query back with
 * {@code isFocusOwner()}. {@link JetControls} does not call {@code
 * isFocusOwner()} either, for the same reason its own render harness could
 * never show a focused control if it did - both read a private flag their own
 * focus listener sets, and {@code dispatchEvent} reaches that listener whether
 * or not a real window is behind the component.
 */
class JetControlsTest {

    private Theme.Mode modeBeforeThisTest;

    @BeforeEach
    void rememberTheCurrentMode() {
        modeBeforeThisTest = Theme.mode();
    }

    @AfterEach
    void restoreTheCurrentMode() {
        Theme.install(modeBeforeThisTest);
    }

    @Test
    void buttonsAreThirtyTwoPixelsHighWithTwelvePixelHorizontalPadding() {
        // Breaks if Button.getPreferredSize stops overriding the height, or if
        // the constructor's border padding is changed from UiTheme.space(12).
        JButton button = JetControls.button("Cancel");

        assertThat(button.getPreferredSize().height).isEqualTo(32);
        Insets insets = button.getInsets();
        assertThat(insets.left).isEqualTo(12);
        assertThat(insets.right).isEqualTo(12);
    }

    @Test
    void aWiderLabelMakesAWiderButtonAtTheSameHeight() {
        // Breaks if getPreferredSize is changed to a fixed Dimension instead of
        // widening super's own text-driven width - every button in the window
        // would then be the same width regardless of its label.
        JButton narrow = JetControls.button("Go");
        JButton wide = JetControls.button("Enrol from all takes");

        assertThat(wide.getPreferredSize().width).isGreaterThan(narrow.getPreferredSize().width);
        assertThat(wide.getPreferredSize().height).isEqualTo(narrow.getPreferredSize().height);
    }

    @Test
    void primaryButtonFillsWithAccentAndSecondaryDoesNot() {
        // Breaks if primaryButton and button stop being told apart - say, both
        // built with primary=false - collapsing "the major action" back into
        // visual sameness with every neutral button beside it.
        JButton primary = JetControls.primaryButton("Use this voice");
        JButton secondary = JetControls.button("Cancel");

        assertThat(primary.getBackground()).isEqualTo(UiTheme.accent());
        assertThat(secondary.getBackground()).isNotEqualTo(UiTheme.accent());
    }

    @Test
    void primaryButtonInkStaysTheSameAcrossBothThemesBecauseAccentDoesToo() {
        // Breaks if the primary button's foreground is changed to UiTheme.ink()
        // or UiTheme.color("text.primary") - either would flip to dark theme's
        // near-white text the moment Theme.install(DARK) runs, at 2.62:1 against
        // the (mode-invariant) accent fill: under the 3:1 floor WCAG allows even
        // for large or bold UI text. See the class javadoc's "primary button's
        // ink" section for the full measurement.
        Theme.install(Theme.Mode.DARK);
        Color darkInk = JetControls.primaryButton("Train the wake word").getForeground();

        Theme.install(Theme.Mode.LIGHT);
        Color lightInk = JetControls.primaryButton("Train the wake word").getForeground();

        assertThat(darkInk).isEqualTo(lightInk);
        assertThat(darkInk).isEqualTo(UiTheme.LIGHT_PALETTE.get("text.primary"));
    }

    @Test
    void primaryButtonHoverAndPressedUseTheDedicatedAccentTokensAndDifferFromRest() {
        // Breaks if hover/pressed on the primary button are collapsed onto the
        // resting accent.primary fill, or onto each other - either would make
        // INTERACTIONS.md's "pressed states should feel immediate" untrue,
        // since nothing would visibly change between renderes.
        JButton button = JetControls.primaryButton("Train the wake word");
        Color resting = button.getBackground();

        button.getModel().setRollover(true);
        Color hover = button.getBackground();
        button.getModel().setRollover(false);

        button.getModel().setArmed(true);
        button.getModel().setPressed(true);
        Color pressed = button.getBackground();

        assertThat(hover).isEqualTo(UiTheme.color("accent.hover")).isNotEqualTo(resting);
        assertThat(pressed).isEqualTo(UiTheme.color("accent.pressed")).isNotEqualTo(resting).isNotEqualTo(hover);
    }

    @Test
    void aFocusedButtonShowsABorderColourNothingElseUses() {
        // Breaks if the FocusListener is removed, or if focusColorFor stops
        // special-casing "focused" and just falls through to the resting
        // border - INTERACTIONS.md's "every keyboard-focusable component needs
        // a visible focus state" would then be silently false for buttons.
        JButton button = JetControls.button("Cancel");
        Color resting = strokeOf(button);

        fireFocus(button, true);
        Color focused = strokeOf(button);

        fireFocus(button, false);
        Color afterBlur = strokeOf(button);

        assertThat(focused).isEqualTo(UiTheme.color("border.focus")).isNotEqualTo(resting);
        assertThat(afterBlur).isEqualTo(resting);
    }

    @Test
    void disabledButtonFadesItsOwnLabelWithoutMakingItInvisible() {
        // Breaks if the disabled branch is deleted (foreground would stay
        // text.primary, no reduction at all) or if it is set equal to the
        // background (reduced to nothing, not merely reduced). The hint label
        // beside a disabled button - VoicePanel's recordReason, useReason and
        // the rest - is a separate UiTheme.hint() label this class does not
        // build and does not dim; confirmed by reading VoicePanel, where
        // record.setEnabled(...) and recordReason.setText(...) never touch each
        // other's colour. This test pins only the button's own label.
        JButton button = JetControls.button("Record takes…");
        Color enabledForeground = button.getForeground();

        button.setEnabled(false);

        assertThat(button.getForeground())
            .isEqualTo(UiTheme.color("text.disabled"))
            .isNotEqualTo(enabledForeground)
            .isNotEqualTo(button.getBackground());
    }

    @Test
    void aDisabledButtonsLabelReachesTheScreenInTextDisabledUnderThePlatformLookAndFeel()
            throws Exception {
        // Breaks if Button hands its label back to the look and feel's
        // delegate. The Windows delegate draws disabled text in its visual
        // style's own grey whatever the foreground says, so the test above,
        // which reads getForeground(), stayed green while every disabled
        // label on screen was the same platform grey in both themes. The
        // platform look and feel is installed here because it is the one the
        // application runs under; the default one would hide the defect.
        LookAndFeel before = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        try {
            for (Theme.Mode mode : Theme.Mode.values()) {
                Theme.install(mode);
                JButton button = JetControls.primaryButton("Enrol from all takes");
                button.setEnabled(false);

                assertThat(dominantInk(button))
                    .as(mode + " disabled label").isEqualTo(UiTheme.color("text.disabled"));
            }
        } finally {
            UIManager.setLookAndFeel(before);
        }
    }

    @Test
    void aSpinnerAndAButtonStrokeTheirOutlineAtTheSameWeightOnAllFourSides() {
        // Breaks if either outline goes back to drawRoundRect on whole pixel
        // coordinates under pure stroke control: at a 2.0 scale the top and
        // left edges then come out one device pixel wide, the bottom and right
        // two, with a pixel of fill showing outside those. Rendered at 2.0,
        // the scale this machine runs at, where one logical pixel is two.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            Color outline = UiTheme.color("border.default");

            assertThat(edgeWeights(spinner(), outline))
                .as(mode + " spinner top, bottom, left, right").containsExactly(2, 2, 2, 2);
            assertThat(edgeWeights(JetControls.button("Record takes…"), outline))
                .as(mode + " button top, bottom, left, right").containsExactly(2, 2, 2, 2);
        }
    }

    @Test
    void textFieldsAreThirtyTwoPixelsHighWithAOnePixelBorder() {
        // Breaks if PlaceholderField stops setting the fixed 32px preferred
        // size, or if the LineBorder's thickness is changed from the
        // BorderFactory.createLineBorder(Color) default of one pixel.
        JTextField field = JetControls.textField("Type a phrase");

        assertThat(field.getPreferredSize().height).isEqualTo(32);
        LineBorder line = (LineBorder) ((CompoundBorder) field.getBorder()).getOutsideBorder();
        assertThat(line.getThickness()).isEqualTo(1);
    }

    @Test
    void aFocusedFieldBorderTurnsBorderFocusAndAnUnfocusedOneDoesNot() {
        // Breaks the same way the button's focus test breaks: remove the
        // FocusListener, or stop branching on the flag it sets, and this field
        // keeps border.default forever, failing INTERACTIONS.md's focus rule.
        JTextField field = JetControls.textField("");
        Color resting = strokeOf(field);

        fireFocus(field, true);
        Color focused = strokeOf(field);

        fireFocus(field, false);
        Color afterBlur = strokeOf(field);

        assertThat(resting).isEqualTo(UiTheme.color("border.default"));
        assertThat(focused).isEqualTo(UiTheme.color("border.focus")).isNotEqualTo(resting);
        assertThat(afterBlur).isEqualTo(resting);
    }

    @Test
    void disabledFieldFadesTextAndBorderWithoutMakingItInvisible() {
        // Breaks if setEnabled(false) is left un-overridden - refresh() would
        // then never run, and a disabled field would still show
        // enabled-looking text.primary and border.default.
        JTextField field = JetControls.textField("");
        Color enabledForeground = field.getForeground();

        field.setEnabled(false);

        assertThat(field.getForeground())
            .isEqualTo(UiTheme.color("text.disabled")).isNotEqualTo(enabledForeground);
        assertThat(readLineOrMatteColor(field.getBorder())).isEqualTo(UiTheme.color("border.subtle"));
    }

    @Test
    void aRegisteredButtonReadsTheNewPaletteAfterAThemeSwitchRatherThanTheOneItWasBuiltUnder() {
        // This is the rule the task's own brief states: a control reads a token
        // when the theme changes, never once at construction. Breaks if
        // Theme.onChange(this::refresh) is deleted from Button's constructor -
        // the button would keep the colour it had when built, exactly the
        // defect Task 2 found and fixed for AuraWindow's own controls.
        Theme.install(Theme.Mode.DARK);
        JButton button = JetControls.button("Cancel");
        Color darkBackground = button.getBackground();

        Theme.install(Theme.Mode.LIGHT);

        assertThat(button.getBackground()).isNotEqualTo(darkBackground);
        assertThat(button.getBackground()).isEqualTo(UiTheme.color("surface.raised"));
    }

    @Test
    void aButtonRemovedFromItsHierarchyStopsTrackingTheTheme() {
        // Breaks if Button stops overriding removeNotify to run the handle
        // Theme.onChange returned - the background would then keep tracking
        // the switch below even after removal, the leak the class javadoc's
        // "returns a handle" paragraph describes fixing. removeNotify is
        // called directly rather than through a real container removal, for
        // the same reason fireFocus below bypasses the real focus manager:
        // none of these components are ever realized in a window in this
        // file, so a container's own remove() would never reach it here
        // whether or not Button overrides removeNotify.
        Theme.install(Theme.Mode.LIGHT);
        JButton button = JetControls.button("Cancel");
        Color lightBackground = button.getBackground();

        button.removeNotify();
        Theme.install(Theme.Mode.DARK);

        assertThat(button.getBackground()).isEqualTo(lightBackground);
    }

    @Test
    void aButtonShownAgainAfterRemovalResumesTrackingTheTheme() {
        // Breaks if addNotify does not re-subscribe after a removeNotify -
        // AuraWindow disposes its frame on close and realises the same tree
        // again on reopen, and a control stuck deaf from that point on would
        // fail silently, in a way no render harness screenshot would catch
        // since nothing throws.
        Theme.install(Theme.Mode.LIGHT);
        JButton button = JetControls.button("Cancel");
        button.removeNotify();
        Theme.install(Theme.Mode.DARK);
        Color whileRemoved = button.getBackground();

        button.addNotify();

        assertThat(button.getBackground())
            .isNotEqualTo(whileRemoved)
            .isEqualTo(UiTheme.color("surface.raised"));
    }

    @Test
    void hoverIsMeasurablyDifferentFromBaseAndCloserToBaseThanSelectionInBothThemes() {
        // Breaks two ways: raising ROW_HOVER_MIX to 1.0 (or beyond) collapses
        // hover onto selection - the first assertion catches that by requiring
        // hover to still differ from selection; dropping ROW_HOVER_MIX to 0
        // (or deleting the hover branch) makes hover indistinguishable from no
        // hover at all - the second assertion catches that by requiring hover
        // to differ from the base surface. Together they pin the "subtler, not
        // absent" reading of INTERACTIONS.md's "hover is lighter than
        // selection" that the class javadoc explains cannot be a literal
        // luminance comparison in both themes at once.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            Color base = UiTheme.surface();
            Color hover = JetControls.rowBackground(false, true);
            Color selected = JetControls.rowBackground(true, false);

            assertThat(hover).as(mode + " hover vs base").isNotEqualTo(base);
            assertThat(hover).as(mode + " hover vs selected").isNotEqualTo(selected);
            assertThat(distance(hover, base))
                .as(mode + ": hover is a subtler move off the base surface than selection is")
                .isLessThan(distance(selected, base));
        }
    }

    @Test
    void selectedRowCarriesAnAccentStripeAndAnUnselectedRowDoesNotJitterForIt() {
        // Breaks if the stripe is painted only as a background colour (no
        // Border change at all - a colour-blind reviewer would then see
        // nothing but the tint DESIGN_RULES.md says not to rely on alone), or
        // if the unselected row's compensating empty border is removed - the
        // second assertion would then catch a row's label jumping sideways by
        // ROW_STRIPE pixels the moment it becomes selected.
        JetControls.RowRenderer<String> renderer = new JetControls.RowRenderer<>() {
            @Override
            protected String text(String value) {
                return value;
            }
        };
        JList<String> list = JetControls.list(new JList<>(new DefaultListModel<>()));

        renderer.getListCellRendererComponent(list, "a", 0, true, false);
        Border selectedBorder = renderer.getBorder();
        renderer.getListCellRendererComponent(list, "a", 0, false, false);
        Border unselectedBorder = renderer.getBorder();

        Border selectedLeading = ((CompoundBorder) selectedBorder).getOutsideBorder();
        Border unselectedLeading = ((CompoundBorder) unselectedBorder).getOutsideBorder();
        assertThat(selectedLeading).isInstanceOf(MatteBorder.class);
        assertThat(((MatteBorder) selectedLeading).getMatteColor()).isEqualTo(UiTheme.accent());
        assertThat(unselectedLeading).isInstanceOf(EmptyBorder.class);

        assertThat(selectedBorder.getBorderInsets(renderer))
            .as("selecting a row must not change its total inset")
            .isEqualTo(unselectedBorder.getBorderInsets(renderer));
    }

    @Test
    void hoveredRowIsNotRepaintedAsSelected() {
        // Breaks if rowBackground's hover branch is reordered above the
        // selected branch, or if RowRenderer stops passing isSelected through
        // to suppress "hovered" on the row that is already selected - either
        // would let a merely-hovered row read as persistently selected.
        assertThat(JetControls.rowBackground(true, true))
            .isEqualTo(JetControls.rowBackground(true, false))
            .as("a selected row ignores hover entirely, it does not add to it")
            .isEqualTo(UiTheme.color("surface.selection"));
    }

    @Test
    void aListWithNoRendererIsStillLegibleInBothThemes() {
        // Breaks if refreshListChrome stops setting the foreground. RowRenderer
        // is opt-in and every caller has to install one; a caller who has not
        // must not be left with the look and feel's own near-black rows on the
        // dark palette's near-black surface.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            JList<String> list = JetControls.list(new JList<>(new DefaultListModel<>()));

            assertThat(list.getForeground())
                .describedAs("row foreground in %s", mode)
                .isEqualTo(UiTheme.color("text.primary"))
                .isNotEqualTo(list.getBackground());
        }
    }

    @Test
    void listRowsAreThirtyTwoPixelsHigh() {
        // Breaks if list() stops calling setFixedCellHeight, or is changed to
        // compactControlHeight() (28) - still inside COMPONENTS.md's
        // documented 28-32 range, but a change worth a reddened test rather
        // than a silent one, since ROW_HEIGHT's own javadoc gives a reason for
        // 32 specifically.
        JList<String> list = JetControls.list(new JList<>(new DefaultListModel<>()));

        assertThat(list.getFixedCellHeight()).isEqualTo(32);
    }

    @Test
    void aFocusedListBorderTurnsBorderFocusEvenWithNothingSelected() {
        // Breaks the same way the button and field focus tests do. Asserted
        // here specifically with an empty model and no selection, because a
        // focus signal carried only by the selected row's stripe would have
        // nothing to show in exactly this case.
        JList<String> list = JetControls.list(new JList<>(new DefaultListModel<>()));
        Color resting = strokeOf(list);

        fireFocus(list, true);

        assertThat(resting).isEqualTo(UiTheme.color("border.subtle"));
        assertThat(strokeOf(list)).isEqualTo(UiTheme.color("border.focus"));
    }

    @Test
    void listRowForegroundFollowsTheListsOwnEnabledStateNotTheRows() {
        // Breaks if RowRenderer reads something other than list.isEnabled() -
        // say, always text.primary - which would stop a disabled list (this
        // application has none yet, but COMPONENTS.md's disabled rule is
        // general, not button-specific) from ever looking disabled at all.
        JetControls.RowRenderer<String> renderer = new JetControls.RowRenderer<>() {
            @Override
            protected String text(String value) {
                return value;
            }
        };
        JList<String> list = JetControls.list(new JList<>(new DefaultListModel<>()));

        list.setEnabled(false);
        renderer.getListCellRendererComponent(list, "a", 0, false, false);
        assertThat(renderer.getForeground()).isEqualTo(UiTheme.color("text.disabled"));

        list.setEnabled(true);
        renderer.getListCellRendererComponent(list, "a", 0, false, false);
        assertThat(renderer.getForeground()).isEqualTo(UiTheme.color("text.primary"));
    }

    @Test
    void aButtonKeepsItsThirtyTwoPixelsInABoxLayoutRow() {
        // Breaks if Button stops pinning its maximum size to its preferred
        // size. BoxLayout never makes a component taller than its maximum, and
        // the delegate's own maximum is the height of the label: every button
        // in such a row came out 24 px, beside 32 px fields in the same row.
        JButton button = JetControls.button("Record takes…");
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(button);
        row.setSize(400, 40);

        row.doLayout();

        assertThat(button.getHeight()).isEqualTo(32);
    }

    @Test
    void aClearCheckBoxSitsOnTheInputSurfaceAndASelectedOneFillsWithAccent() {
        // Breaks if the two states collapse onto one look, or if the selected
        // fill stops being the accent the rest of the window uses for "on".
        JetControls.CheckBox box = checkBox("Listen for the wake word");

        Color clearFill = box.boxFill();
        Color clearOutline = box.boxOutline();
        box.setSelected(true);

        assertThat(clearFill).isEqualTo(UiTheme.color("surface.input"));
        assertThat(clearOutline).isEqualTo(UiTheme.color("border.default"));
        assertThat(box.boxFill()).isEqualTo(UiTheme.accent());
        assertThat(box.markColour()).isNotEqualTo(box.boxFill());
    }

    @Test
    void aDisabledCheckBoxKeepsItsSurfaceAndFadesItsLabelOutlineAndMark() {
        // The button's disabled rule, applied to the check box: the surface
        // stays, the foreground and the outline recede. Breaks if a disabled
        // selected box keeps the accent fill (reads as live) or loses its mark
        // (reads as off while listening is still on).
        JetControls.CheckBox box = checkBox("Listen for the wake word");
        box.setSelected(true);

        box.setEnabled(false);

        assertThat(box.boxFill()).isEqualTo(UiTheme.color("surface.input"));
        assertThat(box.boxOutline()).isEqualTo(UiTheme.color("border.subtle"));
        assertThat(box.markColour()).isEqualTo(UiTheme.color("text.disabled"))
            .isNotEqualTo(box.boxFill());
        assertThat(box.getForeground()).isEqualTo(UiTheme.color("text.disabled"));
    }

    @Test
    void aCheckBoxReadsTheThemeInstalledNowRatherThanTheOneItWasBuiltUnder() {
        // Breaks if the check box caches a colour at construction. It has no
        // Theme.onChange listener to correct one, by design, so a cached
        // colour would stay wrong for the rest of the run.
        Theme.install(Theme.Mode.LIGHT);
        JetControls.CheckBox box = checkBox("Listen for the wake word");

        Theme.install(Theme.Mode.DARK);

        assertThat(box.getForeground()).isEqualTo(UiTheme.DARK_PALETTE.get("text.primary"));
        assertThat(box.boxFill()).isEqualTo(UiTheme.DARK_PALETTE.get("surface.input"));
    }

    @Test
    void spaceTogglesACheckBox() {
        // Breaks if the painted check box loses the platform's key binding,
        // for instance by replacing the delegate with one that installs none.
        JCheckBox box = JetControls.checkBox("Listen for the wake word");
        InputMap keys = box.getInputMap(JComponent.WHEN_FOCUSED);
        ActionEvent event = new ActionEvent(box, ActionEvent.ACTION_PERFORMED, null);

        box.getActionMap().get(keys.get(KeyStroke.getKeyStroke("pressed SPACE"))).actionPerformed(event);
        box.getActionMap().get(keys.get(KeyStroke.getKeyStroke("released SPACE"))).actionPerformed(event);

        assertThat(box.isSelected()).isTrue();
    }

    @Test
    void aFocusedCheckBoxSaysSoAndABlurredOneDoesNot() {
        // Breaks if the focus listener is removed: the ring CheckIcon paints
        // around the square would then never appear.
        JetControls.CheckBox box = checkBox("Listen for the wake word");

        fireFocus(box, true);
        boolean focused = box.focused();
        fireFocus(box, false);

        assertThat(focused).isTrue();
        assertThat(box.focused()).isFalse();
    }

    @Test
    void aSpinnerIsThirtyTwoPixelsHighAndKeepsItsWidthInARowThatOffersMore() {
        // Breaks if Spinner stops overriding the height, or stops pinning its
        // maximum: a spinner in a BoxLayout row with glue would stretch toward
        // the glue and stop looking like a place for two digits.
        JSpinner spinner = JetControls.spinner(new SpinnerNumberModel(20, 1, 50, 1));

        assertThat(spinner.getPreferredSize().height).isEqualTo(32);
        assertThat(spinner.getMaximumSize()).isEqualTo(spinner.getPreferredSize());
    }

    @Test
    void aSpinnerOutlineTurnsBorderFocusWhileItsFieldHasFocus() {
        // Breaks if the focus listener is not installed on the editor's field,
        // which is where focus actually goes: the spinner itself never has it.
        JetControls.Spinner spinner = spinner();
        Color resting = spinner.outline();

        fireFocus(spinner.field(), true);
        Color focused = spinner.outline();
        fireFocus(spinner.field(), false);

        assertThat(resting).isEqualTo(UiTheme.color("border.default"));
        assertThat(focused).isEqualTo(UiTheme.color("border.focus"));
        assertThat(spinner.outline()).isEqualTo(resting);
    }

    @Test
    void aSpinnerKeepsItsOwnArrowsThroughALookAndFeelRefresh() {
        // Breaks if Spinner.updateUI stops installing its own delegate. Every
        // Theme.install runs updateComponentTreeUI, which would otherwise put
        // the platform's bevelled arrow buttons back.
        JSpinner spinner = JetControls.spinner(new SpinnerNumberModel(3, 1, 50, 1));

        SwingUtilities.updateComponentTreeUI(spinner);

        for (Component child : spinner.getComponents()) {
            if (child.getName() != null && child.getName().startsWith("Spinner.")
                    && child.getName().endsWith("Button")) {
                assertThat(child.getClass().getEnclosingClass()).as(child.getName())
                    .isEqualTo(JetControls.class);
            }
        }
        assertThat(spinner.getComponents()).extracting(Component::getName)
            .contains("Spinner.nextButton", "Spinner.previousButton");
    }

    @Test
    void aSpinnerCopiesTheCurrentTextTokenOntoItsFieldWhenItPaints() {
        // Breaks if the field's colours are set once at construction. The
        // field belongs to JSpinner and cannot resolve tokens itself, so a
        // spinner that stopped copying them at paint time would keep the
        // palette it was built under.
        Theme.install(Theme.Mode.LIGHT);
        JetControls.Spinner spinner = spinner();
        spinner.setSize(spinner.getPreferredSize());

        Theme.install(Theme.Mode.DARK);
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            spinner.paintComponent(g);
        } finally {
            g.dispose();
        }

        assertThat(spinner.field().getForeground())
            .isEqualTo(UiTheme.DARK_PALETTE.get("text.primary"));
    }

    @Test
    void aProgressCaptionMeetsTextContrastOnBothSidesOfTheFillInBothThemes() {
        // Breaks if the caption is drawn in one ink. In the dark theme the
        // track wants light text and the fill wants dark: text.primary on the
        // accent is 2.62:1, and dark ink on the dark track is close to 1:1.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            JetControls.ProgressBar bar = (JetControls.ProgressBar) JetControls.progressBar();

            assertThat(contrast(bar.inkOverFill(), bar.fill()))
                .as(mode + " caption over the fill").isGreaterThanOrEqualTo(4.5);
            assertThat(contrast(bar.inkOverTrack(), bar.track()))
                .as(mode + " caption over the track").isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void aProgressBarPaintsNoBevelOfItsOwn() {
        // Breaks if the platform border comes back: the bar would sit in a
        // bevelled frame no other control in the window has.
        JProgressBar bar = JetControls.progressBar();

        assertThat(bar.isBorderPainted()).isFalse();
        assertThat(bar.getBorder()).isNotInstanceOf(javax.swing.plaf.UIResource.class);
    }

    private static JetControls.CheckBox checkBox(String text) {
        return (JetControls.CheckBox) JetControls.checkBox(text);
    }

    private static JetControls.Spinner spinner() {
        return (JetControls.Spinner) JetControls.spinner(new SpinnerNumberModel(3, 1, 50, 1));
    }

    /** Paints a control at its preferred size into an image at {@code scale}. */
    private static BufferedImage render(JComponent control, int scale) {
        control.setSize(control.getPreferredSize());
        BufferedImage image = new BufferedImage(control.getWidth() * scale,
            control.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(scale, scale);
            control.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * The commonest colour inside a button's padding other than its own fill:
     * the ink of its label, since a glyph's solid middle is painted in exactly
     * that colour whatever antialiasing does to its edges.
     */
    private static Color dominantInk(JButton button) {
        int scale = 2;
        BufferedImage image = render(button, scale);
        Insets insets = button.getInsets();
        int fill = button.getBackground().getRGB();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int x = insets.left * scale; x < image.getWidth() - insets.right * scale; x++) {
            for (int y = 2 * scale; y < image.getHeight() - 2 * scale; y++) {
                int rgb = image.getRGB(x, y);
                if (rgb != fill) {
                    counts.merge(rgb, 1, Integer::sum);
                }
            }
        }
        return new Color(Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey());
    }

    /**
     * How many device pixels of {@code outline} each edge shows, counted
     * inward from the top, bottom, left and right at the control's middle, at
     * a 2.0 scale. A pixel of anything else on the outermost row or column
     * counts as zero for that edge.
     */
    private static List<Integer> edgeWeights(JComponent control, Color outline) {
        BufferedImage image = render(control, 2);
        int rgb = outline.getRGB();
        int midX = image.getWidth() / 2;
        int midY = image.getHeight() / 2;
        int top = 0;
        while (image.getRGB(midX, top) == rgb) {
            top++;
        }
        int bottom = 0;
        while (image.getRGB(midX, image.getHeight() - 1 - bottom) == rgb) {
            bottom++;
        }
        int left = 0;
        while (image.getRGB(left, midY) == rgb) {
            left++;
        }
        int right = 0;
        while (image.getRGB(image.getWidth() - 1 - right, midY) == rgb) {
            right++;
        }
        return List.of(top, bottom, left, right);
    }

    /** WCAG 2 contrast ratio, the figure its 4.5:1 floor for body text is stated in. */
    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color colour) {
        return 0.2126 * channel(colour.getRed()) + 0.7152 * channel(colour.getGreen())
            + 0.0722 * channel(colour.getBlue());
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Euclidean distance in RGB space - "how far a colour moved," not which way. */
    private static double distance(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** The stroke colour out of either a plain {@link LineBorder} or a compound one. */
    private static Color readLineOrMatteColor(Border border) {
        Border target = border instanceof CompoundBorder compound ? compound.getOutsideBorder() : border;
        return ((LineBorder) target).getLineColor();
    }

    /**
     * The stroke colour of any control here, whether it sets a border or paints
     * one. A button at TOKENS.md's 6 px radius has to paint its own, so its
     * getBorder() carries only the padding.
     */
    private static Color strokeOf(JComponent control) {
        Object painted = control.getClientProperty(JetControls.BORDER_COLOUR_PROPERTY);
        return painted instanceof Color colour ? colour : readLineOrMatteColor(control.getBorder());
    }

    /**
     * Fires the focus listeners the control installed on itself.
     *
     * <p>Dispatching a FocusEvent at a component that is not showing does not
     * reach it: the keyboard focus manager filters the synthetic event before
     * processFocusEvent runs. Calling the installed listeners is what actually
     * exercises the wiring these controls put in place.
     */
    private static void fireFocus(Component control, boolean gained) {
        FocusEvent event = new FocusEvent(control,
            gained ? FocusEvent.FOCUS_GAINED : FocusEvent.FOCUS_LOST);
        for (FocusListener listener : control.getFocusListeners()) {
            if (gained) {
                listener.focusGained(event);
            } else {
                listener.focusLost(event);
            }
        }
    }
}
