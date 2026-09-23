package aura.app;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws Aura's mark: the owner's sound wave logo, with the running state shown
 * as a small round badge in the bottom right corner.
 *
 * <p>The logo is loaded once from the classpath, cropped to its own alpha
 * bounding box and padded to a centred, transparent square, then scaled down
 * for whichever size the tray or a window asks for. Scaling the source's few
 * hundred pixels down to sixteen in one bilinear step aliases the bars into a
 * smudge, so the image is halved repeatedly, each halving itself bilinear,
 * until it is within two times the requested size, and only the last step
 * lands on the exact size.
 *
 * <p><b>The badge carries the one status worth reading at a distance:</b>
 * whether anything is expected of the user. Only {@link State#WAITING} is
 * meant to catch the eye, big enough to be seen across a room; everything
 * else is information to be read only when someone chooses to look.
 */
public final class TrayIconArt {

    private static final String RESOURCE_NAME = "aura-logo.png";

    /** Reads on both light and dark taskbars without looking disabled. */
    public static final Color IDLE = new Color(0x8B, 0x8D, 0x98);

    /** Indigo violet, distinct from the default blue every tray application uses. */
    public static final Color ACCENT = new Color(0x7C, 0x5C, 0xFF);

    /**
     * Amber, and deliberately not red: colouring a permission question as a failure
     * would teach the user that being asked is bad, and the asking is the feature.
     */
    public static final Color ATTENTION = new Color(0xF5, 0xA5, 0x24);

    /** Kept for things that actually broke, so that it still means something. */
    public static final Color ERROR = new Color(0xE5, 0x48, 0x4D);

    /** What Aura is doing, in the only vocabulary a bottom corner badge can hold. */
    public enum State {
        /** Running; nothing is happening. The plain logo, no badge. */
        READY(IDLE),
        /** An agent is working. */
        WORKING(ACCENT),
        /** Narrating right now. Shares WORKING's badge; the tooltip says which. */
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

    /** The small badge's dot diameter, as a fraction of size. */
    private static final double SMALL_DOT_FRACTION = 0.24;

    /** WAITING's dot: visibly the largest, the one meant to be noticed. */
    private static final double WAITING_DOT_FRACTION = 0.42;

    /** The ring cleared around a badge grows with the dot, as this fraction of
     * its diameter, but never below {@link #MIN_RING_WIDTH}: a small badge on
     * a small icon has a dot only a few pixels across, and a ring scaled down
     * with it would be under a pixel wide, which is no separation from the
     * logo's own colour at all. */
    private static final double RING_WIDTH_FRACTION = 0.15;

    /** The least a cleared ring is ever allowed to be, in pixels, regardless
     * of how small the dot it surrounds is. */
    private static final double MIN_RING_WIDTH = 1.0;

    /** Keeps even the largest badge from touching the image's own edge, without
     * pulling the corner badge back toward the centre more than that. */
    private static final double EDGE_MARGIN_FRACTION = 0.02;

    /** Sizes a window asks for: the title bar, the taskbar and Alt Tab each
     * pick whichever one they need, so every one of them is supplied. */
    private static final int[] WINDOW_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

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

    // Lazily loaded and cropped once, never mutated afterwards: every caller
    // that wants to paint on it works on a copy. Not loaded from a static
    // field initialiser, so a packaging bug that leaves the resource out
    // throws IllegalStateException where a caller can catch it, rather than
    // failing the whole class with ExceptionInInitializerError the first time
    // anything here, including State's own constants, is touched.
    private static volatile BufferedImage baseLogo;

    private static BufferedImage baseLogo() {
        BufferedImage loaded = baseLogo;
        if (loaded == null) {
            synchronized (TrayIconArt.class) {
                loaded = baseLogo;
                if (loaded == null) {
                    loaded = baseLogo = loadBaseLogo();
                }
            }
        }
        return loaded;
    }

    private static BufferedImage loadBaseLogo() {
        URL resource = TrayIconArt.class.getResource(RESOURCE_NAME);
        if (resource == null) {
            throw new IllegalStateException(
                "missing classpath resource aura/app/" + RESOURCE_NAME
                    + ": the application logo was not packaged");
        }
        BufferedImage raw;
        try (InputStream in = resource.openStream()) {
            raw = ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                "could not read classpath resource aura/app/" + RESOURCE_NAME, e);
        }
        if (raw == null) {
            throw new IllegalStateException(
                "classpath resource aura/app/" + RESOURCE_NAME + " is not a readable image");
        }
        return cropToCentredSquare(raw);
    }

    /**
     * Crops to the alpha bounding box and pads it to a centred, transparent
     * square. A tray icon is sixteen to thirty two pixels; spending a quarter
     * of them on the source's own empty margin is the difference between five
     * bars and a smudge, so the box is computed from the pixels rather than
     * assumed.
     */
    private static BufferedImage cropToCentredSquare(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((source.getRGB(x, y) >>> 24) != 0) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            throw new IllegalStateException(
                "classpath resource aura/app/" + RESOURCE_NAME + " is fully transparent");
        }
        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        int side = Math.max(boxWidth, boxHeight);
        int drawX = (side - boxWidth) / 2;
        int drawY = (side - boxHeight) / 2;
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        try {
            g.drawImage(source,
                drawX, drawY, drawX + boxWidth, drawY + boxHeight,
                minX, minY, maxX + 1, maxY + 1,
                null);
        } finally {
            g.dispose();
        }
        return square;
    }

    /**
     * Scales a transparent square image to {@code size}, halving repeatedly
     * with bilinear interpolation until within two times the target, then
     * taking the last step onto the exact size.
     */
    private static BufferedImage scaleSquareTo(BufferedImage source, int size) {
        BufferedImage current = source;
        int currentSize = current.getWidth();
        while (currentSize > size * 2) {
            currentSize = Math.max(size, currentSize / 2);
            current = scaleStep(current, currentSize);
        }
        if (currentSize != size) {
            current = scaleStep(current, size);
        }
        return current;
    }

    private static BufferedImage scaleStep(BufferedImage source, int targetSize) {
        BufferedImage scaled = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, targetSize, targetSize, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
            source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    /** The plain mark, with no state badge, at the given size. */
    public static BufferedImage logo(int size) {
        if (size < 8) {
            // Below this the bars and the badge merge into a blob. Refusing is
            // better than returning something that looks like a rendering bug.
            throw new IllegalArgumentException("tray icon size too small: " + size);
        }
        return scaleSquareTo(baseLogo(), size);
    }

    /**
     * The mark for a given state and size: the plain logo for {@link
     * State#READY}, the logo with a state badge cut and painted into its
     * bottom right corner for every other state.
     */
    public static BufferedImage render(State state, int size) {
        BufferedImage plain = logo(size);
        if (state == State.READY) {
            return plain;
        }
        BufferedImage badged = copy(plain);
        paintBadge(badged, state, size);
        return badged;
    }

    /**
     * The plain logo at the sizes a top level window may be asked to show
     * itself at: the title bar, the taskbar and Alt Tab each pick whichever
     * one they need. One list, so no window repeats it by hand.
     */
    public static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>(WINDOW_ICON_SIZES.length);
        for (int size : WINDOW_ICON_SIZES) {
            icons.add(logo(size));
        }
        return List.copyOf(icons);
    }

    /**
     * The diameter of a state's badge dot, as a fraction of {@code size}.
     * {@link State#WAITING} is deliberately the largest: the one status meant
     * to be noticed across a room rather than read up close.
     */
    static double dotDiameter(State state, int size) {
        return switch (state) {
            case WAITING -> size * WAITING_DOT_FRACTION;
            case WORKING, SPEAKING, ERROR -> size * SMALL_DOT_FRACTION;
            case READY -> 0;
        };
    }

    /**
     * Every badge shares one centre point in the bottom right corner, sized so
     * that even {@link State#WAITING}'s dot, the largest, and the transparent
     * ring cleared around it stay inside the image.
     */
    static double badgeCentre(int size) {
        double waitingDot = size * WAITING_DOT_FRACTION;
        double largestClearRadius = (waitingDot + 2 * ringWidth(waitingDot)) / 2.0;
        double margin = Math.max(0.5, size * EDGE_MARGIN_FRACTION);
        return size - largestClearRadius - margin;
    }

    /**
     * The width of the transparent ring cleared around a dot of this
     * diameter: a fraction of the dot, floored at {@link #MIN_RING_WIDTH} so
     * a small badge on a small icon still separates visibly from the logo
     * underneath rather than clearing a sliver under a pixel wide.
     */
    private static double ringWidth(double dot) {
        return Math.max(MIN_RING_WIDTH, dot * RING_WIDTH_FRACTION);
    }

    /**
     * The logo is itself violet and blue, so a badge placed straight onto it
     * would disappear. A transparent ring a little larger than the dot is
     * cleared through the logo's own pixels first, with {@link
     * AlphaComposite#Clear}, so the badge reads against the taskbar underneath
     * instead of against whatever colour the logo happened to be there.
     */
    private static void paintBadge(BufferedImage image, State state, int size) {
        double dot = dotDiameter(state, size);
        double clear = dot + 2 * ringWidth(dot);
        double centre = badgeCentre(size);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Clear);
            g.fill(new Ellipse2D.Double(centre - clear / 2, centre - clear / 2, clear, clear));
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(state.colour());
            g.fill(new Ellipse2D.Double(centre - dot / 2, centre - dot / 2, dot, dot));
        } finally {
            g.dispose();
        }
    }
}
