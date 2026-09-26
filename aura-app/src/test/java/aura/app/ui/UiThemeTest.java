package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What is worth asserting about a visual language.
 *
 * <p>Not that a colour equals a hex value - that only detects change, and every
 * palette edit would then have to be made twice. What is asserted here is the
 * set of relations the design depends on: that the grid has distinct rungs, that
 * a hint is quieter than body text, that a status stands out, and that the four
 * state colours can be told apart. Each of those is a property a later edit
 * could break while the application still compiles and still looks plausible in
 * a screenshot, which is exactly the kind of breakage a test is for. The comment
 * on each test names the production change it catches.
 */
class UiThemeTest {

    // Theme's mode is process-wide state, not per-test state, because
    // Theme.install(Mode) is the same static call a real window would make.
    // Any test that switches it restores whatever was current before it ran,
    // so test order cannot make a later test start from a mode a different
    // test left behind.
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
        // Breaks if hint() is changed to use INK - the sentence under a control
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
        // state word - "running", "missing" - is the one word on a card a person
        // scans for, and unbolded it sits at the same weight as the label beside
        // it. Body is asserted plain in the same test so that making *everything*
        // bold does not quietly satisfy the first half.
        JLabel status = UiTheme.status("running", "success");
        JLabel body = UiTheme.body("running");

        assertThat(status.getFont().isBold()).isTrue();
        assertThat(body.getFont().isBold()).isFalse();
    }

    @Test
    void theStateColoursCanBeToldApartFromEachOtherAndFromInk() {
        // Breaks if two state colours are collapsed onto one value - WARN set to
        // BAD's red, say, so "missing" and "broken" become the same signal - or
        // if any of them is set to INK, which would make a coloured state word
        // indistinguishable from ordinary text and undo the point of colouring
        // it at all.
        List<Color> states = List.of(
            UiTheme.ACCENT, UiTheme.GOOD, UiTheme.WARN, UiTheme.BAD);

        assertThat(states).doesNotHaveDuplicates();
        assertThat(states).doesNotContain(UiTheme.INK);
    }

    @Test
    void successAndWarningWordsMeetTextContrastOnTheSurfacesTheySitOnInBothThemes() {
        // Breaks if the light palette goes back to TOKENS.md's own success
        // (#4CAF73, 2.73:1 on white) or warning (#D9A441, 2.25:1). Both are
        // only ever the ink of a 13 px word - "missing", "recorded", "running"
        // - on a card or the page, and 13 px text needs 4.5:1. error is left
        // out on purpose: light error measures 3.55:1 on white, also short of
        // 4.5:1, and changing it is a separate decision from this one.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            for (String state : List.of("success", "warning")) {
                for (String surface : List.of("surface.primary", "surface.app")) {
                    assertThat(JetControlsTest.contrast(UiTheme.color(state), UiTheme.color(surface)))
                        .as(mode + " " + state + " on " + surface).isGreaterThanOrEqualTo(4.5);
                }
            }
        }
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

    @Test
    void aWrappedNoteFitsInsideACardAtTheNarrowestTheWindowCanBe() {
        // The one assertion in this file about pixels after layout, and it earns
        // its place: NOTE_WIDTH is a CSS length that Swing scales by the screen's
        // reported resolution, while the room it has to fit in is set by four
        // unrelated numbers - the rail's width, the window's minimum, the
        // column's padding and the card's border. Nothing connects them. Widen
        // the rail, or bump WIDE from 24 to 32 for some other reason, and a
        // sentence starts being cut off mid-word on a screen nobody is watching.
        //
        // Breaks if any of those four grows, or if NOTE_WIDTH does. It caught the
        // original 400, which laid out at 520 device pixels inside a card with
        // 409 to give.
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(UiTheme.pad(UiTheme.WIDE));
        Card card = new Card("Your wake word");
        JLabel note = card.note("Say the wake word the way you would actually say it rather "
            + "than the way you would read it aloud, and vary it: closer and further from "
            + "the laptop, sitting and standing, quietly, in a hurry.");
        column.add(card);

        JScrollPane scroll = new JScrollPane(new ContentPane(column),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // The body of the window at its narrowest: the frame's minimum, less the
        // borders a Windows frame keeps for itself (measured: a frame asked for
        // 720 hands its content pane 708), less the rail and the line beside it.
        // The scrollbar is inside the scroll pane and takes its own width off.
        scroll.setSize(AuraWindow.MINIMUM_WIDTH - 12 - AuraWindow.RAIL_WIDTH - 1,
            AuraWindow.MINIMUM_HEIGHT);
        layOut(scroll);

        int interior = card.getWidth() - card.getInsets().left - card.getInsets().right;
        assertThat(interior).as("a card has some width to work in").isPositive();
        assertThat(note.getPreferredSize().width)
            .as("a wrapped note, rendered, against the room its card has")
            .isLessThanOrEqualTo(interior);
    }

    @Test
    void everyTokenNamedInTokensMdResolvesInBothModesAndThePalettesAgree() {
        // The list below is typed from TOKENS.md itself, independently of
        // whatever UiTheme's own maps happen to contain, so a token misspelled
        // the same way in both DARK_PALETTE and LIGHT_PALETTE - agreeing with
        // each other but not with the design system - still fails here.
        //
        // This list held 21 names, not TOKENS.md's 22, until fix round 1:
        // "surface.editor" was missing from the document's own seven-surface
        // count (app, primary, secondary, raised, input, editor, selection),
        // and a list this test's own equality check is driven from cannot
        // catch its own omission - both counts simply agreed with each other
        // at 21. What would have caught it is counting TOKENS.md's surfaces
        // by hand against this list, which is what fix round 1 did.
        //
        // Breaks if a token is missing from color()'s map for either mode
        // (that token throws in the loop below), or if a token is added to
        // one palette without its twin in the other - the key set comparison
        // catches that even for a token this list does not itself exercise,
        // since DARK_PALETTE.keySet() and LIGHT_PALETTE.keySet() would then
        // differ.
        List<String> tokens = List.of(
            "surface.app", "surface.primary", "surface.secondary", "surface.raised",
            "surface.input", "surface.editor", "surface.selection",
            "border.subtle", "border.default", "border.focus",
            "text.primary", "text.secondary", "text.tertiary", "text.disabled", "text.link",
            "accent.primary", "accent.hover", "accent.pressed",
            "success", "warning", "error", "info");

        assertThat(UiTheme.DARK_PALETTE.keySet())
            .as("dark and light define exactly the same tokens")
            .isEqualTo(UiTheme.LIGHT_PALETTE.keySet());
        assertThat(UiTheme.DARK_PALETTE.keySet())
            .as("the palettes define exactly TOKENS.md's tokens, no more and no fewer")
            .isEqualTo(Set.copyOf(tokens));

        for (String token : tokens) {
            Theme.install(Theme.Mode.DARK);
            assertThat(UiTheme.color(token)).as("dark " + token).isNotNull();
            Theme.install(Theme.Mode.LIGHT);
            assertThat(UiTheme.color(token)).as("light " + token).isNotNull();
        }
    }

    @Test
    void everyNamedAccessorAgreesWithItsStringTokenInBothModes() {
        // Breaks if a named accessor is wired to the wrong token string (say
        // muted() reading "text.tertiary" instead of "text.secondary") or
        // hardcodes a Color instead of delegating to color(String): either
        // way the two APIs the brief asks for would stop agreeing, which is
        // the one thing they are required to do.
        for (Theme.Mode mode : Theme.Mode.values()) {
            Theme.install(mode);
            assertThat(UiTheme.ink()).as(mode + " ink").isEqualTo(UiTheme.color("text.primary"));
            assertThat(UiTheme.muted()).as(mode + " muted").isEqualTo(UiTheme.color("text.secondary"));
            assertThat(UiTheme.line()).as(mode + " line").isEqualTo(UiTheme.color("border.subtle"));
            assertThat(UiTheme.canvas()).as(mode + " canvas").isEqualTo(UiTheme.color("surface.app"));
            assertThat(UiTheme.surface()).as(mode + " surface").isEqualTo(UiTheme.color("surface.primary"));
            assertThat(UiTheme.accent()).as(mode + " accent").isEqualTo(UiTheme.color("accent.primary"));
            assertThat(UiTheme.good()).as(mode + " good").isEqualTo(UiTheme.color("success"));
            assertThat(UiTheme.warn()).as(mode + " warn").isEqualTo(UiTheme.color("warning"));
            assertThat(UiTheme.bad()).as(mode + " bad").isEqualTo(UiTheme.color("error"));
        }
    }

    @Test
    void switchingModeChangesSurfaceAndTextThenRestoresThemExactly() {
        // Breaks if install(Mode) stops actually flipping which map color()
        // reads from - say, if it only updated UIManager and left the mode
        // field alone - in which case the "different" assertions below would
        // fail, or if the two palettes were accidentally given the same
        // values for these tokens, in which case they would also fail.
        Theme.install(Theme.Mode.LIGHT);
        Color lightSurface = UiTheme.surface();
        Color lightText = UiTheme.ink();

        Theme.install(Theme.Mode.DARK);
        Color darkSurface = UiTheme.surface();
        Color darkText = UiTheme.ink();
        assertThat(darkSurface).isNotEqualTo(lightSurface);
        assertThat(darkText).isNotEqualTo(lightText);

        Theme.install(Theme.Mode.LIGHT);
        assertThat(UiTheme.surface()).as("surface restored").isEqualTo(lightSurface);
        assertThat(UiTheme.ink()).as("text restored").isEqualTo(lightText);
    }

    @Test
    void spaceAnswersOnlyTheStepsTokensMdSupports() {
        // Breaks if space(int) is changed to accept an arbitrary integer and
        // hand it back unchanged (inventing a value the design system never
        // sanctioned), or if it is changed to JetTheme's own scheme, an index
        // 0..6,8,10,12 mapped to a pixel value - under that scheme space(4)
        // would answer 16, not 4, and space(40) would throw, since 40 is not
        // one of JetTheme's indices.
        for (int step : List.of(0, 4, 8, 12, 16, 20, 24, 32, 40, 48)) {
            assertThat(UiTheme.space(step)).isEqualTo(step);
        }

        assertThatThrownBy(() -> UiTheme.space(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiTheme.space(28)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void radiusAnswersOnlyTheValuesTokensMdSupports() {
        // Same shape as spaceAnswersOnlyTheStepsTokensMdSupports, for the same
        // reason: Task 3 needs a 6px button radius and a 4-6px field radius
        // to come from somewhere that cannot invent a fifth value nobody put
        // in TOKENS.md. Breaks if radius(int) starts handing back whatever it
        // is given instead of checking it against the documented set.
        for (int px : List.of(0, 4, 6, 8)) {
            assertThat(UiTheme.radius(px)).isEqualTo(px);
        }

        assertThatThrownBy(() -> UiTheme.radius(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiTheme.radius(10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theThreeControlHeightsAreDistinctAndAscending() {
        // Breaks if two of the three heights are given the same value, or if
        // largeControlHeight() is made smaller than controlHeight() (or
        // either smaller than compactControlHeight()), the same class of
        // mistake theSpacingGridIsDistinctAndAscending guards against for
        // the spacing grid.
        List<Integer> heights = List.of(
            UiTheme.compactControlHeight(), UiTheme.controlHeight(), UiTheme.largeControlHeight());

        assertThat(heights).containsExactly(28, 32, 36);
        assertThat(heights).doesNotHaveDuplicates();
        assertThat(heights).isSorted();
    }

    @Test
    void onChangeListenersRunOnASwitchAndSurviveAThrowingOne() {
        // Breaks if Theme.install stops calling its listeners at all (both
        // "first" and "third" would then be missing), or if the try/catch
        // around each listener is removed, or the loop is wrapped in one
        // try/catch instead of one per listener - either way the throwing
        // listener in the middle would stop "third" from ever running. This
        // is the same property SidecarEventsTest checks for a subscriber that
        // throws, and for the same reason: a broken panel must not be able to
        // freeze the window for every other panel.
        // All three are unregistered through the handle Theme.onChange
        // returns once this test has its answer, rather than left registered
        // for the rest of the suite - the broken one would otherwise log a
        // warning on every later test's mode switch.
        List<String> calls = new ArrayList<>();
        Runnable first = Theme.onChange(() -> calls.add("first"));
        Runnable broken = Theme.onChange(() -> {
            throw new RuntimeException("a broken listener");
        });
        Runnable third = Theme.onChange(() -> calls.add("third"));

        try {
            Theme.install(Theme.Mode.DARK);
            assertThat(calls).containsExactly("first", "third");
        } finally {
            first.run();
            broken.run();
            third.run();
        }
    }

    @Test
    void aRemovedListenerIsNotCalledAndRemovingOneDoesNotDisturbTheOthers() {
        // Pins the handle Theme.onChange returns: breaks if its run() is a
        // no-op (calls would then contain "removed" too), or if removing one
        // listener also drops an unrelated one from the same list (calls
        // would then be missing "kept").
        List<String> calls = new ArrayList<>();
        Runnable kept = Theme.onChange(() -> calls.add("kept"));
        Runnable removed = Theme.onChange(() -> calls.add("removed"));

        removed.run();
        try {
            Theme.install(Theme.Mode.DARK);
            assertThat(calls).containsExactly("kept");
        } finally {
            kept.run();
        }
    }

    @Test
    void aPanelRegisteredThroughOnChangeIsCalledAndReadsTheNewPaletteAfterASwitch() {
        // This is the rule Task 2's brief states and every later task follows: a
        // component reads a token when the theme changes, never once at
        // construction. Breaks if a panel like this one - built while DARK is
        // current, then registering an onChange listener that reads
        // UiTheme.canvas() again - is written instead to cache the colour it saw
        // at construction and never told to look again: the listener would still
        // run (onChangeListenersRunOnASwitchAndSurviveAThrowingOne already
        // covers that), but the panel's own background would stay dark, and
        // "the colours afterwards come from the new palette" is exactly the
        // half of the contract that test does not check.
        Theme.install(Theme.Mode.DARK);
        JPanel panel = new JPanel();
        panel.setBackground(UiTheme.canvas());
        boolean[] called = {false};
        Theme.onChange(() -> {
            called[0] = true;
            panel.setBackground(UiTheme.canvas());
        });

        Theme.install(Theme.Mode.LIGHT);

        assertThat(called[0]).as("the registered listener ran").isTrue();
        assertThat(panel.getBackground())
            .as("the panel's colour after the switch")
            .isEqualTo(UiTheme.LIGHT_PALETTE.get("surface.app"));
    }

    @Test
    void installNoLongerMakesPerComponentDefaultsModeDependent() {
        // Fix round 1 on Task 2: rendering a real AuraWindow through
        // install(DARK), install(LIGHT) and install(DARK) again found every
        // button in the window washed out to near-illegibility in both
        // modes. The cause was this method's own applyToLookAndFeel pushing
        // Button.foreground (and, the same shape, CheckBox/TextField/List/
        // Spinner/ProgressBar/Label's foreground and a few backgrounds) into
        // UIManager: SwingUtilities.updateComponentTreeUI only copies a
        // UIManager colour into a component's own cached field the first
        // time its updateUI() runs after the key is already correct, not on
        // every later install, so a button attached during the first (dark)
        // install kept dark's near-white text forever, unreadable against
        // its own native, never-themed, always-light face in either mode.
        //
        // Breaks if any of these keys is ever again made to track the
        // palette through UIManager: the value captured after install(DARK)
        // would then differ from the value after install(LIGHT), which is
        // exactly the state fix round 1's render caught.
        //
        // Panel/Viewport/ScrollPane.background joined this list in fix round
        // 2: kept in round 1 on the reasoning that every panel and scroll
        // pane already sets its own background, which missed
        // TasksPanel.activityCard()'s inner scroll - an empty top border
        // whose gap the wrapped list's own background does not reach, so an
        // opaque JScrollPane painted that gap from whichever mode installed
        // first and never again. Rendered as a solid bar under "Activity"
        // that survived a switch to light and a switch back.
        List<String> keys = List.of(
            "Label.foreground",
            "Button.foreground",
            "CheckBox.foreground",
            "TextField.foreground", "TextField.background", "TextField.caretForeground",
            "List.foreground", "List.background",
            "List.selectionForeground", "List.selectionBackground",
            "Spinner.foreground", "Spinner.background",
            "ProgressBar.foreground", "ProgressBar.background",
            "Panel.background", "Viewport.background", "ScrollPane.background");

        Theme.install(Theme.Mode.DARK);
        Map<String, Object> afterDark = new LinkedHashMap<>();
        for (String key : keys) {
            afterDark.put(key, UIManager.get(key));
        }

        Theme.install(Theme.Mode.LIGHT);
        for (String key : keys) {
            assertThat(UIManager.get(key)).as(key).isEqualTo(afterDark.get(key));
        }
    }

    /**
     * Lays a tree out without a native window, so this runs anywhere the rest of
     * the suite does. {@code validate()} needs a peer; {@code doLayout} does not.
     */
    private static void layOut(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                layOut(container);
            }
        }
    }

    /** Perceived lightness, so "quieter" is a measurable claim rather than a hex diff. */
    private static double brightness(Color colour) {
        return 0.299 * colour.getRed() + 0.587 * colour.getGreen() + 0.114 * colour.getBlue();
    }

    @Test
    void aFactoryLabelFollowsAThemeSwitchRatherThanTheModeItWasBuiltUnder() {
        // Breaks if styled() goes back to setForeground at construction. A
        // colour read once is right until the theme changes and wrong forever
        // after, and the look and feel's own propagation does not correct it:
        // it reaches a component only the first time updateUI runs after
        // attachment.
        Theme.install(Theme.Mode.LIGHT);
        JLabel label = UiTheme.body("Speaker model");
        Color underLight = label.getForeground();

        Theme.install(Theme.Mode.DARK);

        assertThat(underLight).isEqualTo(UiTheme.LIGHT_PALETTE.get("text.primary"));
        assertThat(label.getForeground()).isEqualTo(UiTheme.DARK_PALETTE.get("text.primary"))
            .isNotEqualTo(underLight);
    }

    @Test
    void aColourTheCallerSetsWinsAndStopsTracking() {
        // Breaks if setForeground stops clearing the token. A renderer that
        // paints one row in the error colour means that row, and a label that
        // silently went back to text.primary on the next switch would lose it.
        Theme.install(Theme.Mode.LIGHT);
        JLabel label = UiTheme.body("failed");
        label.setForeground(UiTheme.color("error"));
        Color chosen = label.getForeground();

        Theme.install(Theme.Mode.DARK);

        assertThat(chosen).isEqualTo(UiTheme.LIGHT_PALETTE.get("error"));
        assertThat(label.getForeground()).isEqualTo(chosen);
    }

    @Test
    void recolourPointsALabelAtANewTokenThatGoesOnFollowingTheTheme() {
        // Breaks if recolour resolves the token and hands the label a Color:
        // the label would be right until the next switch and wrong after it,
        // which is the frozen-constant defect VoicePanel's state words had.
        // text.primary rather than a state token, because success, warning
        // and error are the same hex in both palettes and could not tell a
        // frozen colour from a tracked one.
        Theme.install(Theme.Mode.LIGHT);
        JLabel label = UiTheme.status("0 recordings", "text.secondary");

        UiTheme.recolour(label, "text.primary");
        Color underLight = label.getForeground();
        Theme.install(Theme.Mode.DARK);

        assertThat(underLight).isEqualTo(UiTheme.LIGHT_PALETTE.get("text.primary"));
        assertThat(label.getForeground()).isEqualTo(UiTheme.DARK_PALETTE.get("text.primary"));
    }

    @Test
    void recolourResumesTrackingOnALabelAnExplicitColourHadStopped() {
        // Breaks if recolour leaves a label alone once setForeground has
        // cleared its token. Naming a token is a request to track it.
        Theme.install(Theme.Mode.LIGHT);
        JLabel label = UiTheme.body("failed");
        label.setForeground(UiTheme.color("error"));

        UiTheme.recolour(label, "text.secondary");
        Theme.install(Theme.Mode.DARK);

        assertThat(label.getForeground()).isEqualTo(UiTheme.DARK_PALETTE.get("text.secondary"));
    }

    @Test
    void recolourRefusesALabelThisClassDidNotBuildAndSaysWhatItWas() {
        // Breaks if recolour falls back to setForeground on a plain JLabel:
        // the colour would freeze at the current mode, silently.
        JLabel plain = new JLabel("missing");

        assertThatThrownBy(() -> UiTheme.recolour(plain, "warning"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(JLabel.class.getName());
    }

    @Test
    void recolourRefusesAnUnknownTokenAndLeavesTheLabelAsItWas() {
        // Breaks if the token is stored before it is checked: the label would
        // throw on its next paint instead of here, far from the typo.
        Theme.install(Theme.Mode.LIGHT);
        JLabel label = UiTheme.status("recorded", "success");

        assertThatThrownBy(() -> UiTheme.recolour(label, "sucess"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sucess");
        assertThat(label.getForeground()).isEqualTo(UiTheme.color("success"));
    }
}
