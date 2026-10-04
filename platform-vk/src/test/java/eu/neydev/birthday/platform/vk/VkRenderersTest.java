package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.InlineKeyboard;
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
     * VK answers error 911 ("buttons contain too much rows") to an inline keyboard
     * past six rows, and the language picker arrives with seven: five rows of two,
     * the page counter and the way back. The mapper folds the counter into the
     * footer instead of losing the message whole; every button survives the fit.
     */
    @Test
    void sevenRowPickerFoldsIntoTheVkInlineLimit() throws Exception {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int pair = 0; pair < 5; pair++) {
            rows.add(List.of(
                    InlineKeyboard.KeyboardButton.callback("Language " + pair + "a", "la" + pair),
                    InlineKeyboard.KeyboardButton.callback("Language " + pair + "b", "lb" + pair)));
        }

        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("←", "lp0"),
                InlineKeyboard.KeyboardButton.callback("1/4", "np"),
                InlineKeyboard.KeyboardButton.callback("→", "lp1")));
        rows.add(List.of(InlineKeyboard.KeyboardButton.callback("Back", "bk")));

        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(new InlineKeyboard(rows)));

        assertThat(json.path("buttons")).hasSize(6);
        assertThat(payloads(json.path("buttons").path(5))).containsExactly("lp0", "np", "lp1", "bk");

    }

    /**
     * The zone picker puts a caption longer than a phone half-row on a row of its
     * own, and the Americas page is mostly long captions: nine rows reach the
     * mapper. The counter folds into the footer, the singles re-pair two by two,
     * and all eleven actions arrive inside six rows of at most five buttons.
     */
    @Test
    void longZoneSinglesRePairIntoSixRows() throws Exception {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (String zone : List.of("Los Angeles UTC-7", "Mexico City UTC-6", "Sao Paulo UTC-3",
                "Buenos Aires UTC-3", "Cairo UTC+2", "Johannesburg UTC+2", "UTC UTC+0")) {
            rows.add(List.of(InlineKeyboard.KeyboardButton.callback(zone, "zp:" + zone)));
        }

        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("←", "zp4"),
                InlineKeyboard.KeyboardButton.callback("6/6", "np")));
        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback("Enter my own", "zm"),
                InlineKeyboard.KeyboardButton.callback("Back", "bk")));

        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(new InlineKeyboard(rows)));
        JsonNode buttons = json.path("buttons");

        assertThat(buttons).hasSize(6);

        for (JsonNode row : buttons) {
            assertThat(row.size()).isLessThanOrEqualTo(VkKeyboardMapper.MAX_BUTTONS_IN_ROW);
        }

        List<String> all = new ArrayList<>();
        buttons.forEach(row -> all.addAll(payloads(row)));
        assertThat(all).hasSize(11).contains("np", "zm", "bk");
        assertThat(payloads(buttons.path(5))).containsExactly("zp4", "np", "zm", "bk");

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
