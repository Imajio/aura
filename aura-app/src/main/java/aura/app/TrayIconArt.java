package aura.app;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * Draws Aura's mark: an open ring with a point at its centre.
 *
 * <p>The ring is the aura, the point is the thing being watched. It is drawn rather
 * than shipped as a bitmap so it renders at whatever size the taskbar asks for -
 * 16 at 100%, 20 at 125%, 24 at 150%, 32 at 200%. A 16-pixel image stretched to 24
 * is what makes a tray icon look like a smudge.
 *
 * <p>The ring is left open at the top. A closed circle is a status light and a
 * hundred other applications; the gap makes it a particular shape at a glance.
 *
 * <p><b>The icon carries the one status worth reading at a distance:</b> whether
 * anything is expected of the user. Only {@link State#WAITING} is meant to catch
 * the eye - everything else is information they can look at when they choose to.
 */
public final class TrayIconArt {

    /** Reads on both light and dark taskbars without looking disabled. */
    public static final Color IDLE = new Color(0x8B, 0x8D, 0x98);

    /** Indigo-violet, distinct from the default blue every tray application uses. */
    public static final Color ACCENT = new Color(0x7C, 0x5C, 0xFF);

    /**
     * Amber, and deliberately not red: colouring a permission question as a failure
     * would teach the user that being asked is bad, and the asking is the feature.
     */
    public static final Color ATTENTION = new Color(0xF5, 0xA5, 0x24);

    /** Kept for things that actually broke, so that it still means something. */
    public static final Color ERROR = new Color(0xE5, 0x48, 0x4D);

    /** What Aura is doing, in the only vocabulary sixteen pixels can hold. */
    public enum State {
        /** Running; nothing is happening. */
        READY(IDLE),
        /** An agent is working. */
        WORKING(ACCENT),
        /** Narrating right now. */
        SPEAKING(ACCENT),
        /** The user is being waited on. The one state meant to be noticed. */
        WAITING(ATTENTION),
        /** Something failed, and was said once. */
        ERROR(TrayIconArt.ERROR);

        private final Color colour;

        State(Color colour) {
            this.colour = colour;
        }

        public Color colour() {
            return colour;
        }
    }

    private TrayIconArt() {
    }

    /**
     * What the user should see when this happens, or null to leave the icon alone.
     *
     * <p>Null is the honest answer for a kind that says nothing about status. An
     * unrecognised line becomes {@code OTHER}; painting it as work, or as a failure,
     * would be inventing a status out of not having one.
     */
    public static State stateFor(aura.core.EventKind kind) {
        return switch (kind) {
            case PERMISSION_REQUEST -> State.WAITING;
            case ERROR -> State.ERROR;
            case DONE -> State.READY;
            case SESSION_START, TOOL_START, TOOL_END, TEST_RESULT,
                 SUBAGENT_START, SUBAGENT_END -> State.WORKING;
            case ASSISTANT_TEXT, OTHER -> null;
        };
    }

    public static BufferedImage render(State state, int size) {
        if (size < 8) {
            // Below this the ring and the point merge into a blob. Refusing is
            // better than returning something that looks like a rendering bug.
            throw new IllegalArgumentException("tray icon size too small: " + size);
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
            g.setColor(state.colour());

            double stroke = Math.max(1.5, size / 9.0);
            double inset = stroke;
            double diameter = size - inset * 2;

            drawRing(g, state, inset, diameter, stroke);
            drawCentre(g, state, size);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawRing(Graphics2D g, State state, double inset,
                                 double diameter, double stroke) {
        // The gap sits at the top, where a break reads as intentional rather than
        // as a clipped edge.
        double sweep = state == State.ERROR ? 200 : 300;
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(inset, inset, diameter, diameter, 120, sweep, Arc2D.OPEN));

        if (state == State.SPEAKING) {
            // A second arc outside the first: sound leaving the ring. Thinner, so
            // the mark still reads as one shape rather than two circles.
            double outer = stroke * 0.6;
            g.setStroke(new BasicStroke((float) outer, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(inset / 3, inset / 3,
                diameter + inset * 1.3, diameter + inset * 1.3, 320, 80, Arc2D.OPEN));
        }
    }

    private static void drawCentre(Graphics2D g, State state, int size) {
        double dot = switch (state) {
            // Large enough to be the thing the eye lands on: this is the state that
            // has to be noticeable across a room.
            case WAITING -> size / 2.6;
            case WORKING, SPEAKING, ERROR -> size / 4.0;
            case READY -> size / 5.5;
        };
        double centre = size / 2.0;
        g.fill(new Ellipse2D.Double(centre - dot / 2, centre - dot / 2, dot, dot));
    }
}
