package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.text.RichText;

import java.util.List;

/**
 * Building WhatsApp outbound payloads: text or interactive (up to 3 reply buttons).
 * WhatsApp has no url buttons, so they expand into lines of text.
 */
public final class WhatsAppPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REPLY_BUTTONS = 3;
    private static final int BUTTON_TITLE_MAX = 20;

    private WhatsAppPayloadBuilder() {
    }

    public static ObjectNode buildSend(String to, RichText text, InlineKeyboard keyboard) {

        List<InlineKeyboard.KeyboardButton> callbacks = keyboard.rows().stream()
                .flatMap(List::stream)
                .filter(button -> button.actionId() != null)
                .toList();

        List<InlineKeyboard.KeyboardButton> urls = keyboard.rows().stream()
                .flatMap(List::stream)
                .filter(button -> button.url() != null)
                .toList();

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);

        if (!callbacks.isEmpty() && callbacks.size() <= MAX_REPLY_BUTTONS) {
            return withInteractiveButtons(payload, plainText(text, urls), callbacks);
        }

        payload.put("type", "text");
        payload.putObject("text").put("body", plainText(text, urls));

        return payload;

    }

    private static ObjectNode withInteractiveButtons(ObjectNode payload, String text,
                                                     List<InlineKeyboard.KeyboardButton> callbacks) {
        payload.put("type", "interactive");
        ObjectNode interactive = payload.putObject("interactive");
        interactive.putObject("body").put("text", text);

        ObjectNode action = interactive.putObject("action");
        var buttons = action.putArray("buttons");

        for (InlineKeyboard.KeyboardButton button : callbacks) {
            ObjectNode reply = buttons.addObject();
            reply.put("type", "reply");
            ObjectNode replyBody = reply.putObject("reply");
            replyBody.put("id", button.actionId());
            replyBody.put("title", truncate(button.label(), BUTTON_TITLE_MAX));
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

    private static String truncate(String label, int max) {
        return label.length() <= max ? label : label.substring(0, max - 1) + "…";
    }

}
