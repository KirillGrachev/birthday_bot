package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.text.RichText;
import org.junit.jupiter.api.Test;

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

    @Test
    void plainTextDegradesLinks() {
        String text = VkTextRenderer.render(RichText.parse("*bold* and [site](https://x.y)"));
        assertThat(text).isEqualTo("bold and site (https://x.y)");
    }

}
