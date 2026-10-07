package eu.neydev.birthday.core.api;

/**
 * Captions as the platforms see them. Every platform has its own character limit and
 * clips the overflow itself, most of them silently and without an ellipsis, so a caption
 * that is one character too long turns into a different word on somebody's screen.
 * Truncating here keeps the clipping ours: the same ellipsis everywhere, and the limit
 * of each platform written down next to its mapper.
 */
public final class ButtonLabels {

    /** The single character that says "there was more here". */
    private static final String ELLIPSIS = "…";

    private ButtonLabels() {
    }

    public static String truncate(String label, int max) {

        if (label == null) {
            return "";
        }

        if (label.length() <= max) {
            return label;
        }

        // A limit of one still has to leave room for the ellipsis itself.
        return max <= ELLIPSIS.length()
                ? label.substring(0, max)
                : label.substring(0, max - ELLIPSIS.length()) + ELLIPSIS;

    }

    public static boolean clipped(String label, int max) {
        return label != null && label.length() > max;
    }

}
