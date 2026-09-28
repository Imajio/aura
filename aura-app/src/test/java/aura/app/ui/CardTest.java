package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

/**
 * A card's rows at a width short of what they ask for. Laid out with {@code doLayout}
 * rather than in a window, because GridBagLayout needs no peer to place a row.
 */
class CardTest {

    /** A card's width at the window's minimum size: a 498 px column less its 24 px padding. */
    private static final int NARROWEST_CARD = 450;

    @Test
    void aNameKeepsItsColumnWhenItsRowIsShortOfRoom() {
        // Breaks if the name column's minimum goes back to its text: below about
        // 870 px the Log card's "Folder" fell to 36 of its 140 px and its path
        // started 104 px left of every other card's value.
        Card log = new Card("Log");
        JLabel path = log.line("Folder",
            UiTheme.elastic(UiTheme.body("C:\\Users\\somebody\\AppData\\Local\\Aura\\logs\\and\\further")),
            new JButton("Open log folder"));
        Card listening = new Card("Listening");
        JLabel microphone = listening.line("Microphone", UiTheme.status("off", "text.secondary"), null);

        layOut(log);
        layOut(listening);

        JLabel folder = nameBefore(log, path);
        assertThat(log.getPreferredSize().width).as("the row really is short of room")
            .isGreaterThan(NARROWEST_CARD);
        assertThat(folder.getWidth()).isEqualTo(folder.getPreferredSize().width);
        assertThat(path.getX()).as("the value column starts at the same x in both cards")
            .isEqualTo(microphone.getX());
    }

    @Test
    void aReasonBesideAButtonWrapsInsteadOfLosingItsEnd() {
        // Breaks if the reason goes back to an ellipsised label: at the minimum
        // size the Listening card read "there is no wake-word model to lis...",
        // and that sentence is the one thing on the card that names the next step.
        Card listening = new Card("Listening");
        listening.line("Microphone", UiTheme.status("off", "text.secondary"), null);
        JButton setUp = new JButton("Set this up");
        String sentence = "there is no wake-word model to listen for";
        JLabel why = listening.line("Why not", UiTheme.fitted(sentence, "text.primary"), setUp);
        int oneLineHeight = why.getPreferredSize().height;

        layOut(listening);
        // Twice, as the running window does: the first pass hands the sentence its
        // width, and the second gives it the height that width takes.
        layOut(listening);

        assertThat(nameBefore(listening, why).getWidth())
            .isEqualTo(nameBefore(listening, why).getPreferredSize().width);
        assertThat(why.getText()).as("wrapped").startsWith("<html>").contains(sentence);
        assertThat(why.getHeight()).as("on more than one line").isGreaterThan(oneLineHeight);
        assertThat(why.getX() + why.getWidth()).as("clear of its button").isLessThanOrEqualTo(setUp.getX());
        assertThat(setUp.getWidth()).isEqualTo(setUp.getPreferredSize().width);
    }

    private static void layOut(Card card) {
        card.setSize(NARROWEST_CARD, card.getPreferredSize().height);
        card.doLayout();
    }

    /** The name label a row placed immediately before its value. */
    private static JLabel nameBefore(Card card, JComponent value) {
        for (int i = 1; i < card.getComponentCount(); i++) {
            if (card.getComponent(i) == value) {
                return (JLabel) card.getComponent(i - 1);
            }
        }
        throw new AssertionError("not in the card");
    }
}
