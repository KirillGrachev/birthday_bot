package eu.neydev.birthday.platform.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.ButtonLabels;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardFitter;
import eu.neydev.birthday.core.text.RichText;

/**
 * Rendering core structures into Slack formats: mrkdwn text and Block Kit blocks, one
 * actions block per core keyboard row.
 *
 * <p>Slack is the roomiest of the six platforms here: fifty blocks a message, twenty-five
 * elements an actions block, seventy-five characters a button. The rows are still folded
 * to that geometry, so a future picker cannot outgrow it silently.
 */
public final class SlackRenderers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Slack refuses a message with more than fifty blocks. */
    static final int MAX_BLOCKS = 50;

    /** An actions block stops at twenty-five interactive elements. */
    static final int MAX_ELEMENTS_IN_BLOCK = 25;

    /** A button caption longer than this is refused, not clipped. */
    static final int MAX_BUTTON_TEXT = 75;

    private SlackRenderers() {
    }

    public static String mrkdwn(RichText text) {

        StringBuilder sb = new StringBuilder();

        for (RichText.Segment segment : text.segments()) {
            switch (segment) {
                case RichText.Segment.Text t -> sb.append(escape(t.value()));
                case RichText.Segment.Bold b -> sb.append('*').append(b.value()).append('*');
                case RichText.Segment.Italic i -> sb.append('_').append(i.value()).append('_');
                case RichText.Segment.Code c -> sb.append('`').append(c.value()).append('`');
                case RichText.Segment.Link l -> sb.append('<').append(l.url())
                        .append('|').append(l.label()).append('>');
            }
        }

        return sb.toString();

    }

    public static ArrayNode blocks(InlineKeyboard keyboard) {

        ArrayNode blocks = MAPPER.createArrayNode();

        if (keyboard.isEmpty()) {
            return blocks;
        }

        for (var row : KeyboardFitter.fit(keyboard.rows(), MAX_BLOCKS, MAX_ELEMENTS_IN_BLOCK)) {

            ObjectNode block = blocks.addObject();
            block.put("type", "actions");
            ArrayNode elements = block.putArray("elements");

            for (var button : row) {

                ObjectNode element = elements.addObject();
                element.put("type", "button");

                // The caption is a text object, not a bare string: Slack answers a string
                // with invalid_blocks, and the whole message never reaches the reader.
                ObjectNode text = element.putObject("text");
                text.put("type", "plain_text");
                text.put("text", ButtonLabels.truncate(button.label(), MAX_BUTTON_TEXT));
                text.put("emoji", true);

                if (button.url() != null) {
                    element.put("url", button.url());
                } else {
                    element.put("action_id", button.actionId());
                    element.put("value", button.actionId());
                }

                // An empty style is not a neutral style, it is an unknown one: the
                // secondary buttons simply carry no style at all.
                switch (button.style()) {
                    case PRIMARY -> element.put("style", "primary");
                    case DANGER -> element.put("style", "danger");
                    case SECONDARY -> { }
                }

            }

        }

        return blocks;

    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
