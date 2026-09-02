package aura.agents;

/**
 * Shrinks text down to narrator-hint size.
 *
 * <p>Lives on its own because both adapters need it: keeping a copy in each
 * would duplicate the same logical block verbatim.
 */
final class SummaryText {

    private static final int MAX_LENGTH = 200;

    private SummaryText() {
    }

    /** Flattens line breaks and cuts to {@value #MAX_LENGTH} characters. */
    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= MAX_LENGTH ? flat : flat.substring(0, MAX_LENGTH) + "…";
    }
}
