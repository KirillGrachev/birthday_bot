package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.ButtonLabels;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.text.RichText;

import java.util.List;

/**
 * Building WhatsApp outbound payloads: plain text, up to three reply buttons, or a list
 * message for anything wider. WhatsApp has no url buttons, so links expand into lines of
 * text under the body.
 *
 * <p>Three buttons used to be the whole budget, and a keyboard that did not fit them was
 * sent as text with no buttons at all: the main menu arrived, and there was nothing on it
 * to press. A list message is WhatsApp's own answer to a wider keyboard - one control
 * opens a sheet of rows - so the pickers stay tappable here too. Ten rows is the entire
 * budget of a list, which is why the core pages the pickers five choices at a time for
 * this platform: five choices, the navigation row, the free-text zone prompt and the way
 * back come to exactly ten.
 */
public final class WhatsAppPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reply buttons are the cheapest control, and WhatsApp allows three of them. */
    static final int MAX_REPLY_BUTTONS = 3;

    /** A reply button title is clipped past twenty characters. */
    static final int BUTTON_TITLE_MAX = 20;

    /** A list message carries ten rows across all its sections, and no more. */
    static final int LIST_ROWS_MAX = 10;

    /** The control that opens the list is captioned with twenty characters. */
    static final int LIST_BUTTON_MAX = 20;

    /** A row title is clipped past twenty-four characters. */
    static final int LIST_ROW_TITLE_MAX = 24;

    /** The row description underneath has room for seventy-two. */
    static final int LIST_ROW_DESCRIPTION_MAX = 72;

    /** The caption of the list control when a keyboard arrives without one. */
    private static final String FALLBACK_LIST_LABEL = "OK";

    private WhatsAppPayloadBuilder() {
    }

    public static ObjectNode buildSend(String to, RichText text, InlineKeyboard keyboard) {

        List<InlineKeyboard.KeyboardButton> callbacks = keyboard.callbacks();
        String body = plainText(text, keyboard.links());

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);

        if (callbacks.isEmpty()) {
            payload.put("type", "text");
            payload.putObject("text").put("body", body);
            return payload;
        }

        if (callbacks.size() <= MAX_REPLY_BUTTONS) {
            return withReplyButtons(payload, body, callbacks);
        }

        return withList(payload, body, keyboard.listLabel(), callbacks);

    }

    private static ObjectNode withReplyButtons(ObjectNode payload, String body,
                                               List<InlineKeyboard.KeyboardButton> callbacks) {

        payload.put("type", "interactive");
        ObjectNode interactive = payload.putObject("interactive");
        interactive.put("type", "button");
        interactive.putObject("body").put("text", body);

        ObjectNode action = interactive.putObject("action");
        var buttons = action.putArray("buttons");

        for (InlineKeyboard.KeyboardButton button : callbacks) {

            ObjectNode reply = buttons.addObject();
            reply.put("type", "reply");

            ObjectNode replyBody = reply.putObject("reply");
            replyBody.put("id", button.actionId());
            replyBody.put("title", ButtonLabels.truncate(button.label(), BUTTON_TITLE_MAX));

        }

        return payload;

    }

    private static ObjectNode withList(ObjectNode payload, String body, String listLabel,
                                       List<InlineKeyboard.KeyboardButton> callbacks) {

        payload.put("type", "interactive");
        ObjectNode interactive = payload.putObject("interactive");
        interactive.put("type", "list");
        interactive.putObject("body").put("text", body);

        ObjectNode action = interactive.putObject("action");
        action.put("button", ButtonLabels.truncate(
                listLabel == null || listLabel.isBlank() ? FALLBACK_LIST_LABEL : listLabel,
                LIST_BUTTON_MAX));

        // One section is enough: ten rows is the limit of the whole list, so splitting
        // would buy nothing. A lone section carries no title of its own.
        ArrayNode rows = action.putArray("sections").addObject().putArray("rows");

        for (InlineKeyboard.KeyboardButton button : callbacks) {

            ObjectNode row = rows.addObject();
            row.put("id", button.actionId());
            row.put("title", ButtonLabels.truncate(button.label(), LIST_ROW_TITLE_MAX));

            // A caption that had to be clipped keeps its full text one line below: the
            // description is room WhatsApp gives a row for free, and a truncated city
            // name is a guess the reader should not have to make.
            if (ButtonLabels.clipped(button.label(), LIST_ROW_TITLE_MAX)) {
                row.put("description",
                        ButtonLabels.truncate(button.label(), LIST_ROW_DESCRIPTION_MAX));
            }

        }

        return payload;

    }

    private static String plainText(RichText text, List<InlineKeyboard.KeyboardButton> urls) {

        StringBuilder sb = new StringBuilder(text.toPlainText());

        for (InlineKeyboard.KeyboardButton button : urls) {
            sb.append('\n').append(button.label()).append(": ").append(button.url());
        }

        return sb.toString();

    }

}
