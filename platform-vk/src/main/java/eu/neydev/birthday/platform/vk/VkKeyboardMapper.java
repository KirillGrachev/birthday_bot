package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.InlineKeyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Core keyboard -> VK inline keyboard. Actions are encoded into the payload
 * ({@code {"a":"<actionId>"}}) - the 255-byte payload limit is met with short ids.
 * Captions are truncated to 40 characters (the VK limit).
 *
 * <p>The geometry is VK's own: an inline keyboard carries at most six rows of at most
 * five buttons, and a keyboard that does not fit is rejected whole with error 911
 * ("buttons contain too much rows") - both pickers arrive with seven rows or more.
 * The mapper folds rows to fit and never drops one, so every action survives.
 */
public final class VkKeyboardMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An inline keyboard stops at six rows; a default (non-inline) one would get ten. */
    static final int MAX_INLINE_ROWS = 6;

    /** A row wider than five buttons is rejected with the same error 911. */
    static final int MAX_BUTTONS_IN_ROW = 5;

    private VkKeyboardMapper() {
    }

    public static String map(InlineKeyboard keyboard) {

        if (keyboard.isEmpty()) {
            return "";
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("one_time", false);
        root.put("inline", true);
        ArrayNode rows = root.putArray("buttons");

        for (var row : fitInlineRows(keyboard.rows())) {

            ArrayNode rowNode = rows.addArray();

            for (var button : row) {

                ObjectNode buttonNode = rowNode.addObject();
                ObjectNode action = buttonNode.putObject("action");

                action.put("label", truncate(button.label(), 40));

                if (button.url() != null) {
                    action.put("type", "open_link");
                    action.put("link", button.url());
                    // VK rejects a color on open_link with error 911: the tint is
                    // a property of the pressable button, and a link button is not one.
                } else {

                    action.put("type", "callback");
                    ObjectNode payload = MAPPER.createObjectNode();
                    payload.put("a", button.actionId());
                    action.set("payload", payload);

                    buttonNode.put("color", switch (button.style()) {
                        case PRIMARY -> "primary";
                        case DANGER -> "negative";
                        case SECONDARY -> "secondary";
                    });

                }

            }

        }

        return root.toString();

    }

    /**
     * Rows are folded until the keyboard fits the inline limit. The two bottom rows
     * become one first: in the pickers that is the page counter and the footer under
     * it, so the content grid stays intact. Then the narrowest pair of one-button
     * rows re-pairs - the phone-width rule of the core puts a long caption on a row
     * of its own, and VK would rather show two of them side by side than reject the
     * message whole. Rows of any shape merge last, narrowest adjacent pair first,
     * and a merge that cannot happen leaves the keyboard as it is: VK then answers
     * 911, which the dispatcher reports honestly instead of retrying.
     */
    static List<List<InlineKeyboard.KeyboardButton>> fitInlineRows(
            List<List<InlineKeyboard.KeyboardButton>> rows) {

        if (rows.size() <= MAX_INLINE_ROWS) {
            return rows;
        }

        List<List<InlineKeyboard.KeyboardButton>> fitted = new ArrayList<>(rows);
        foldBottomRows(fitted);

        while (fitted.size() > MAX_INLINE_ROWS) {

            if (!mergeNarrowestAdjacent(fitted, true) && !mergeNarrowestAdjacent(fitted, false)) {
                break;
            }

        }

        return fitted;

    }

    private static void foldBottomRows(List<List<InlineKeyboard.KeyboardButton>> rows) {

        if (rows.size() < 2) {
            return;
        }

        List<InlineKeyboard.KeyboardButton> above = rows.get(rows.size() - 2);
        List<InlineKeyboard.KeyboardButton> last = rows.get(rows.size() - 1);

        if (above.size() + last.size() > MAX_BUTTONS_IN_ROW) {
            return;
        }

        List<InlineKeyboard.KeyboardButton> merged = new ArrayList<>(above);
        merged.addAll(last);
        rows.set(rows.size() - 2, List.copyOf(merged));
        rows.remove(rows.size() - 1);

    }

    private static boolean mergeNarrowestAdjacent(List<List<InlineKeyboard.KeyboardButton>> rows,
                                                  boolean singleButtonRowsOnly) {

        int best = -1;
        int bestWidth = Integer.MAX_VALUE;

        for (int i = 0; i + 1 < rows.size(); i++) {

            List<InlineKeyboard.KeyboardButton> left = rows.get(i);
            List<InlineKeyboard.KeyboardButton> right = rows.get(i + 1);

            if (left.size() + right.size() > MAX_BUTTONS_IN_ROW) {
                continue;
            }

            if (singleButtonRowsOnly && (left.size() != 1 || right.size() != 1)) {
                continue;
            }

            int width = captionWidth(left) + captionWidth(right);

            if (width < bestWidth) {
                bestWidth = width;
                best = i;
            }

        }

        if (best < 0) {
            return false;
        }

        List<InlineKeyboard.KeyboardButton> merged = new ArrayList<>(rows.get(best));
        merged.addAll(rows.get(best + 1));
        rows.set(best, List.copyOf(merged));
        rows.remove(best + 1);

        return true;

    }

    private static int captionWidth(List<InlineKeyboard.KeyboardButton> row) {

        int width = 0;

        for (InlineKeyboard.KeyboardButton button : row) {
            width += button.label().length();
        }

        return width;

    }

    private static String truncate(String label, int max) {
        return label.length() <= max ? label : label.substring(0, max - 1) + "…";
    }

}
