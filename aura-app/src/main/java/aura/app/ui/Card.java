package aura.app.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One bordered card: a heading, then rows of name, state and an optional button,
 * on a grid so that every state word in a card starts at the same x whatever the
 * names beside them are.
 *
 * <p>Shared rather than owned by one panel. It began inside {@link StatusPanel},
 * and the second section that wanted a card had the choice of copying it or
 * moving it here — and two copies of a layout drift into two subtly different
 * ones, which reads down the page as two screens built by different people.
 *
 * <p>A card is built once and then written to, not rebuilt. {@code StatusPanel}
 * throws its cards away and makes new ones on every sidecar event, which is fine
 * for a panel made only of labels; {@link VoicePanel} cannot, because a rebuild
 * would take a spinner the owner had just set back to its default. So {@link
 * #line} and {@link #note} hand back the component they added, for a caller that
 * means to keep the handle and change its text later.
 */
final class Card extends JPanel {

    /**
     * Wide enough for the longest row label the cards use.
     *
     * <p>One number for the whole application, not one per panel: each card is
     * its own grid, and left to themselves the grids find as many different
     * widths for their name column as there are cards — which reads, down the
     * page, as several things built separately rather than one screen.
     */
    private static final int NAME_WIDTH = 140;

    private int row;

    Card(String heading) {
        setLayout(new GridBagLayout());
        setBackground(Color.WHITE);
        setBorder(UiTheme.card());
        setAlignmentX(Component.LEFT_ALIGNMENT);
        add(UiTheme.heading(heading), 0, 3, false);
        row++;
    }

    @Override
    public Dimension getMaximumSize() {
        // Full width, natural height. Without this a BoxLayout column either
        // centres the card at its preferred width or stretches it to the height
        // of the window, and both look like a bug.
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** A row of name, state and an optional action. Returns the state component. */
    <T extends JComponent> T line(String name, T state, JComponent action) {
        add(nameColumn(name), 0, 1, false);
        // The state column is the one that both grows and gives way, so it
        // carries the row's weight and is filled to its cell. Both halves of
        // that matter. GridBagLayout hands a zero-weight column exactly its
        // minimum whenever the row is short of space, so weight on the button
        // instead would leave a long value at nothing while the button's column
        // swallowed the window; and a cell at fill NONE keeps its preferred
        // width whatever the cell can spare, which is how a long value comes to
        // overrun the column beside it rather than ellipsise inside its own.
        add(state, 1, action == null ? 2 : 1, true);
        if (action != null) {
            add(action, 2, 1, false);
        }
        row++;
        return state;
    }

    /** A sentence across the card's whole width. Returns the label it added. */
    JLabel note(String text) {
        return note(UiTheme.wrapped(text));
    }

    /** The same, for a note whose text and colour the caller means to change later. */
    JLabel note(JLabel prepared) {
        add(prepared, 0, 3, false);
        row++;
        return prepared;
    }

    /**
     * A row of controls across the card's whole width.
     *
     * <p>For the rows that are not a name and a value — a spinner beside a
     * button, a button beside the reason it is disabled. The content lays itself
     * out; the card only decides where the row sits.
     */
    <T extends JComponent> T row(T content) {
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Elastic, unlike a note: a row of controls is the one thing in a card
        // that has something to do with the width it is given. A progress bar
        // told to be its preferred size is 145 pixels of bar with its own
        // caption spilling over the end, and a row of buttons ends in a glue
        // that swallows whatever it does not need.
        add(content, 0, 3, true);
        row++;
        return content;
    }

    private void add(JComponent component, int x, int width, boolean elastic) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = row;
        c.gridwidth = width;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(row == 0 ? 0 : UiTheme.GAP, x == 0 ? 0 : UiTheme.WIDE, 0, 0);
        // Something in the grid must carry weight, or GridBagLayout centres the
        // whole thing in the card instead of packing it to the left.
        c.weightx = elastic ? 1 : 0;
        c.fill = elastic ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        add(component, c);
    }

    /**
     * A row label of fixed width, so that the state words in every card start at
     * the same x.
     */
    private static JLabel nameColumn(String name) {
        JLabel label = UiTheme.body(name);
        Dimension natural = label.getPreferredSize();
        // max, not a flat NAME_WIDTH: a label longer than the column would
        // otherwise be cut off in silence. This way a name that outgrows the
        // column pushes its own row wider — visibly out of line with the rest,
        // which is a bug report rather than a missing word.
        label.setPreferredSize(new Dimension(Math.max(NAME_WIDTH, natural.width), natural.height));
        return label;
    }
}
