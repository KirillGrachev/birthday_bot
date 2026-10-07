package eu.neydev.birthday.platform.slack;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Block Kit contract: a button's caption is a text object, not a bare string, and a
 * secondary button carries no style at all. Slack answers either mistake with
 * {@code invalid_blocks}, which means the whole message never reaches the reader - a
 * defect that is invisible in our logs and total on screen.
 */
class SlackRenderersTest {

    private static List<String> actionIds(JsonNode blocks) {

        List<String> ids = new ArrayList<>();

        for (JsonNode block : blocks) {
            for (JsonNode element : block.path("elements")) {

                if (element.has("action_id")) {
                    ids.add(element.path("action_id").asText());
                } else {
                    ids.add(element.path("url").asText());
                }

            }
        }

        return ids;

    }

    @Test
    void buttonsCarryATextObjectAndNoEmptyStyle() {

        JsonNode blocks = SlackRenderers.blocks(new InlineKeyboard(List.of(List.of(
                InlineKeyboard.KeyboardButton.callback("Date", "sd",
                        InlineKeyboard.KeyboardButton.Style.PRIMARY),
                InlineKeyboard.KeyboardButton.callback("Zone", "zn"),
                InlineKeyboard.KeyboardButton.callback("Delete", "dd",
                        InlineKeyboard.KeyboardButton.Style.DANGER),
                InlineKeyboard.KeyboardButton.url("GitHub", "https://g.h")))));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("actions");

        JsonNode elements = blocks.get(0).path("elements");
        assertThat(elements).hasSize(4);

        for (JsonNode element : elements) {

            assertThat(element.path("type").asText()).isEqualTo("button");
            assertThat(element.path("text").path("type").asText()).isEqualTo("plain_text");
            assertThat(element.path("text").path("text").asText()).isNotEmpty();

            if (element.has("style")) {
                assertThat(element.path("style").asText()).isIn("primary", "danger");
            }

        }

        assertThat(elements.get(0).path("style").asText()).isEqualTo("primary");
        assertThat(elements.get(1).has("style")).isFalse();
        assertThat(elements.get(2).path("style").asText()).isEqualTo("danger");
        assertThat(elements.get(3).path("url").asText()).isEqualTo("https://g.h");
        assertThat(actionIds(blocks)).containsExactly("sd", "zn", "dd", "https://g.h");

    }

    @Test
    void emptyKeyboardMeansNoBlocksAtAll() {
        assertThat(SlackRenderers.blocks(InlineKeyboard.empty())).isEmpty();
    }

    /** Slack is roomy, but the folding is shared, so a tall picker arrives folded anyway. */
    @Test
    void everyRowBecomesOneActionsBlock() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int pair = 0; pair < 5; pair++) {
            rows.add(List.of(
                    InlineKeyboard.KeyboardButton.callback("Language " + pair + "a", "la" + pair),
                    InlineKeyboard.KeyboardButton.callback("Language " + pair + "b", "lb" + pair)));
        }

        rows.add(List.of(InlineKeyboard.KeyboardButton.callback("Back", "bk")));
        JsonNode blocks = SlackRenderers.blocks(new InlineKeyboard(rows));

        assertThat(blocks).hasSizeLessThanOrEqualTo(SlackRenderers.MAX_BLOCKS);

        int elements = 0;

        for (JsonNode block : blocks) {

            assertThat(block.path("type").asText()).isEqualTo("actions");
            assertThat(block.path("elements")).hasSizeLessThanOrEqualTo(SlackRenderers.MAX_ELEMENTS_IN_BLOCK);
            elements += block.path("elements").size();

        }

        assertThat(elements).isEqualTo(11);
        assertThat(actionIds(blocks)).contains("bk", "la0", "lb4");

    }

    @Test
    void aLongCaptionIsTruncatedToTheSlackLimit() {

        JsonNode blocks = SlackRenderers.blocks(new InlineKeyboard(List.of(List.of(
                InlineKeyboard.KeyboardButton.callback("x".repeat(120), "sd")))));

        assertThat(blocks.get(0).path("elements").get(0).path("text").path("text").asText())
                .hasSize(SlackRenderers.MAX_BUTTON_TEXT)
                .endsWith("…");

    }

}
