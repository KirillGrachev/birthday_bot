package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.ButtonLabels;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardFitter;

/**
 * Core keyboard -> VK inline keyboard. Actions are encoded into the payload
 * ({@code {"a":"<actionId>"}}) - the 255-byte payload limit is met with short ids.
 * Captions are truncated to 40 characters (the VK limit).
 *
 * <p>The geometry is VK's own, and it is three numbers rather than one: an inline keyboard
 * carries at most six rows of at most five buttons, and at most ten buttons whole, however
 * they are arranged. VK refuses a keyboard past any of them with error 911, and it names
 * the limit it hit - "buttons contain too much rows" for the rows, "keyboard contains too
 * much buttons" for the count.
 *
 * <p>{@link KeyboardFitter} folds the rows to fit and never drops one, so every action
 * survives. The count it cannot fold: a fold rearranges buttons, and the pickers arrive
 * with fourteen of them unless the core pages them down first, which it does to VK's own
 * budget ({@code KeyboardLimits.of(VK)}). What is left here is the row geometry alone.
 */
public final class VkKeyboardMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An inline keyboard stops at six rows; a default (non-inline) one would get ten. */
    static final int MAX_INLINE_ROWS = 6;

    /** A row wider than five buttons is rejected with the same error 911. */
    static final int MAX_BUTTONS_IN_ROW = 5;

    /** The caption length VK shows whole on a button. */
    static final int MAX_LABEL = 40;

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

        for (var row : KeyboardFitter.fit(keyboard.rows(), MAX_INLINE_ROWS, MAX_BUTTONS_IN_ROW)) {

            ArrayNode rowNode = rows.addArray();

            for (var button : row) {

                ObjectNode buttonNode = rowNode.addObject();
                ObjectNode action = buttonNode.putObject("action");

                action.put("label", ButtonLabels.truncate(button.label(), MAX_LABEL));

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

}
