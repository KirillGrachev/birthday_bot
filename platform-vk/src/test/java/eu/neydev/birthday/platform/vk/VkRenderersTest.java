package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardLimits;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.text.RichText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VkRenderersTest {

    @Test
    void keyboardJsonStructure() throws Exception {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("Date", "sd",
                        InlineKeyboard.KeyboardButton.Style.PRIMARY))));
        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(keyboard));

        assertThat(json.path("inline").asBoolean()).isTrue();
        JsonNode button = json.path("buttons").path(0).path(0);
        assertThat(button.path("action").path("type").asText()).isEqualTo("callback");
        assertThat(button.path("action").path("payload").path("a").asText()).isEqualTo("sd");
        assertThat(button.path("color").asText()).isEqualTo("primary");

    }

    /**
     * VK answers a colored open_link button with error 911 and the message never
     * arrives: the tint belongs to callback buttons alone. The regression came from
     * the main menu, whose GitHub star button sits at row four, column one.
     */
    @Test
    void linkButtonsCarryNoColor() throws Exception {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("About", "ab"),
                        InlineKeyboard.KeyboardButton.url("Star on GitHub", "https://github.com/x/y"))));
        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(keyboard));

        JsonNode callback = json.path("buttons").path(0).path(0);
        JsonNode link = json.path("buttons").path(0).path(1);
        assertThat(callback.path("color").asText()).isEqualTo("secondary");
        assertThat(link.path("action").path("type").asText()).isEqualTo("open_link");
        assertThat(link.has("color")).isFalse();

    }

    @Test
    void longLabelsTruncatedToVkLimit() throws Exception {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("x".repeat(80), "sd"))));
        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(keyboard));
        assertThat(json.path("buttons").path(0).path(0).path("action").path("label").asText())
                .hasSize(40);

    }

    /**
     * VK answers error 911 ("buttons contain too much rows") to an inline keyboard past
     * six rows, and a page of six long language names is eight of them: six singles, the
     * page counter and the way back. The mapper folds the counter into the footer and
     * re-pairs the singles, so all ten buttons - VK counts ten, and a page never carries
     * more - arrive inside six rows of at most five.
     */
    @Test
    void aTallLanguagePageFoldsIntoTheVkInlineLimit() throws Exception {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (String language : List.of("Португальский", "Нидерландский", "Английский",
                "Французский", "Итальянский", "Испанский")) {
            rows.add(List.of(InlineKeyboard.KeyboardButton.callback(language, "l:" + language)));
        }

        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("←", "lp0"),
                InlineKeyboard.KeyboardButton.callback("2/6", "np"),
                InlineKeyboard.KeyboardButton.callback("→", "lp1")));
        rows.add(List.of(InlineKeyboard.KeyboardButton.callback("Back", "bk")));

        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(new InlineKeyboard(rows)));
        JsonNode buttons = json.path("buttons");

        assertThat(buttons).hasSize(VkKeyboardMapper.MAX_INLINE_ROWS);
        assertThat(counted(buttons))
                .isEqualTo(10)
                .isLessThanOrEqualTo(KeyboardLimits.of(Platform.VK).maxButtons());
        assertThat(payloads(buttons.path(5))).containsExactly("lp0", "np", "lp1", "bk");

    }

    /**
     * The zone picker puts a caption longer than a phone half-row on a row of its own,
     * and the Americas page is mostly long captions: seven rows reach the mapper, five
     * zones plus the counter and the free-text prompt under it. The counter folds into
     * the footer, and all ten actions arrive inside six rows of at most five buttons.
     */
    @Test
    void longZoneSinglesFoldIntoSixRows() throws Exception {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (String zone : List.of("Los Angeles UTC-7", "Mexico City UTC-6", "Sao Paulo UTC-3",
                "Buenos Aires UTC-3", "New York UTC-4")) {
            rows.add(List.of(InlineKeyboard.KeyboardButton.callback(zone, "zp:" + zone)));
        }

        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("←", "zp4"),
                InlineKeyboard.KeyboardButton.callback("6/12", "np"),
                InlineKeyboard.KeyboardButton.callback("→", "zp5")));
        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("Enter my own", "zm"),
                InlineKeyboard.KeyboardButton.callback("Back", "bk")));

        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(new InlineKeyboard(rows)));
        JsonNode buttons = json.path("buttons");

        assertThat(buttons).hasSize(VkKeyboardMapper.MAX_INLINE_ROWS);

        for (JsonNode row : buttons) {
            assertThat(row.size()).isLessThanOrEqualTo(VkKeyboardMapper.MAX_BUTTONS_IN_ROW);
        }

        assertThat(counted(buttons))
                .isEqualTo(10)
                .isLessThanOrEqualTo(KeyboardLimits.of(Platform.VK).maxButtons());
        assertThat(payloads(buttons.path(5))).containsExactly("zp4", "np", "zp5", "zm", "bk");

    }

    private static int counted(JsonNode buttons) {

        int total = 0;

        for (JsonNode row : buttons) {
            total += row.size();
        }

        return total;

    }

    /** A keyboard of six rows or fewer is VK-legal as it is: the mapper keeps the shape. */
    @Test
    void keyboardWithinSixRowsKeepsItsShape() throws Exception {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int single = 0; single < 4; single++) {
            rows.add(List.of(InlineKeyboard.KeyboardButton.callback("Row " + single, "r" + single)));
        }

        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("←", "lp0"),
                InlineKeyboard.KeyboardButton.callback("4/4", "np"),
                InlineKeyboard.KeyboardButton.callback("→", "lp1")));
        rows.add(List.of(InlineKeyboard.KeyboardButton.callback("Back", "bk")));

        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(new InlineKeyboard(rows)));

        assertThat(json.path("buttons")).hasSize(6);
        assertThat(payloads(json.path("buttons").path(4))).containsExactly("lp0", "np", "lp1");
        assertThat(payloads(json.path("buttons").path(5))).containsExactly("bk");

    }

    private static List<String> payloads(JsonNode row) {

        List<String> ids = new ArrayList<>();
        row.forEach(button -> ids.add(button.path("action").path("payload").path("a").asText()));

        return ids;

    }

    @Test
    void plainTextDegradesLinks() {
        String text = VkTextRenderer.render(RichText.parse("*bold* and [site](https://x.y)"));
        assertThat(text).isEqualTo("bold and site (https://x.y)");
    }

}
