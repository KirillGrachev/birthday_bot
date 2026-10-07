package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.text.RichText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payload contract of WhatsApp: three reply buttons while the keyboard is narrow, a
 * list message once it is not, and a plain message when there is nothing to press.
 *
 * <p>The list is what keeps a menu alive here. Three buttons used to be the whole budget,
 * and a keyboard of four was sent as text with no controls at all: the reader got a menu
 * and nothing on it to press. A list message carries ten rows, which is why the core
 * pages the pickers five choices at a time for this platform.
 */
class WhatsAppPayloadBuilderTest {

    private static InlineKeyboard.KeyboardButton callback(String label, String id) {
        return InlineKeyboard.KeyboardButton.callback(label, id);
    }

    private static InlineKeyboard keyboardOf(int callbacks) {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int index = 0; index < callbacks; index++) {
            rows.add(List.of(callback("Item " + index, "id" + index)));
        }

        return new InlineKeyboard(rows).withListLabel("Choose");

    }

    private static ObjectNode send(InlineKeyboard keyboard) {
        return WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("text"), keyboard);
    }

    @Test
    void aNarrowKeyboardStaysThreeReplyButtons() {

        JsonNode payload = send(keyboardOf(3));

        assertThat(payload.path("type").asText()).isEqualTo("interactive");
        assertThat(payload.path("interactive").path("type").asText()).isEqualTo("button");
        assertThat(payload.path("interactive").path("body").path("text").asText()).isEqualTo("text");

        JsonNode buttons = payload.path("interactive").path("action").path("buttons");
        assertThat(buttons).hasSize(3);
        assertThat(buttons.get(0).path("type").asText()).isEqualTo("reply");
        assertThat(buttons.get(0).path("reply").path("id").asText()).isEqualTo("id0");
        assertThat(buttons.get(0).path("reply").path("title").asText()).isEqualTo("Item 0");

    }

    @Test
    void aWiderKeyboardBecomesAListAndKeepsEveryChoice() {

        JsonNode payload = send(keyboardOf(10));

        assertThat(payload.path("interactive").path("type").asText()).isEqualTo("list");

        JsonNode action = payload.path("interactive").path("action");
        assertThat(action.path("button").asText()).isEqualTo("Choose");

        JsonNode sections = action.path("sections");
        assertThat(sections).hasSize(1);

        JsonNode rows = sections.get(0).path("rows");
        assertThat(rows).hasSize(10);

        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asText()));
        assertThat(ids).containsExactly("id0", "id1", "id2", "id3", "id4",
                "id5", "id6", "id7", "id8", "id9");

    }

    @Test
    void aKeyboardWithoutChoicesIsPlainText() {

        JsonNode payload = send(InlineKeyboard.empty());

        assertThat(payload.path("type").asText()).isEqualTo("text");
        assertThat(payload.path("text").path("body").asText()).isEqualTo("text");

    }

    /** WhatsApp has no link buttons, so a link becomes a line of text under the body. */
    @Test
    void linksDegradeIntoLinesOfText() {

        JsonNode payload = send(new InlineKeyboard(List.of(
                List.of(callback("Menu", "mo"),
                        InlineKeyboard.KeyboardButton.url("GitHub", "https://g.h")))));

        assertThat(payload.path("interactive").path("body").path("text").asText())
                .isEqualTo("text\nGitHub: https://g.h");

    }

    @Test
    void aLongRowTitleKeepsItsFullTextInTheDescription() {

        JsonNode payload = send(new InlineKeyboard(List.of(
                List.of(callback("• Cathair Mheicsiceo UTC-6", "zp:mex"),
                        callback("Back", "bk"),
                        callback("Next", "zg:1"),
                        callback("Prev", "zg:0")))).withListLabel("Choose"));

        JsonNode rows = payload.path("interactive").path("action").path("sections")
                .get(0).path("rows");

        assertThat(rows.get(0).path("title").asText())
                .hasSize(WhatsAppPayloadBuilder.LIST_ROW_TITLE_MAX)
                .endsWith("…");
        assertThat(rows.get(0).path("description").asText()).isEqualTo("• Cathair Mheicsiceo UTC-6");
        assertThat(rows.get(1).path("description").isMissingNode()).isTrue();

    }

    @Test
    void aKeyboardWithoutAListLabelStillOpens() {

        JsonNode payload = WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("text"),
                new InlineKeyboard(List.of(
                        List.of(callback("a", "a"), callback("b", "b"),
                                callback("c", "c"), callback("d", "d")))));

        assertThat(payload.path("interactive").path("action").path("button").asText()).isNotEmpty();

    }

    @Test
    void aLongListLabelIsClippedToTheControlLimit() {

        JsonNode payload = WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("text"),
                keyboardOf(4).withListLabel("x".repeat(60)));

        assertThat(payload.path("interactive").path("action").path("button").asText())
                .hasSize(WhatsAppPayloadBuilder.LIST_BUTTON_MAX);

    }

}
