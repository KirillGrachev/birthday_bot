package eu.neydev.birthday.platform.discord;

import eu.neydev.birthday.core.api.InlineKeyboard;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapping keyboards into Discord components (no gateway - a pure function). Discord
 * enforces its geometry inside JDA, before a request is even built, so an oversized
 * keyboard would surface as an exception in the outbound thread rather than as an error
 * response: the rows are folded here instead.
 */
class DiscordKeyboardMapperTest {

    private static InlineKeyboard.KeyboardButton callback(String label, String id) {
        return InlineKeyboard.KeyboardButton.callback(label, id);
    }

    private static List<Button> buttons(ActionRow row) {

        List<Button> buttons = new ArrayList<>();
        row.getComponents().forEach(component -> buttons.add((Button) component));

        return buttons;

    }

    @Test
    void mapsCallbacksAndUrlsWithStyles() {

        List<ActionRow> rows = DiscordKeyboardMapper.components(new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("Date", "sd",
                                InlineKeyboard.KeyboardButton.Style.PRIMARY),
                        InlineKeyboard.KeyboardButton.callback("Delete", "dl",
                                InlineKeyboard.KeyboardButton.Style.DANGER),
                        InlineKeyboard.KeyboardButton.url("Git", "https://g.h")))));

        assertThat(rows).hasSize(1);
        List<Button> components = buttons(rows.get(0));

        assertThat(components).hasSize(3);
        Button first = components.get(0);
        Button link = components.get(2);

        assertThat(first.getCustomId()).isEqualTo("sd");
        assertThat(first.getStyle()).isEqualTo(net.dv8tion.jda.api.components.buttons.ButtonStyle.PRIMARY);
        assertThat(link.getUrl()).isEqualTo("https://g.h");

    }

    @Test
    void emptyKeyboardMapsToNoRows() {
        assertThat(DiscordKeyboardMapper.components(InlineKeyboard.empty())).isEmpty();
    }

    /**
     * A picker page is seven rows: five pairs of languages, the page counter and the way
     * back. Discord stops at five action rows, so the counter folds into the footer and
     * the pairs re-pair - all fourteen actions still arrive.
     */
    @Test
    void aSevenRowPickerFoldsIntoFiveActionRows() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int pair = 0; pair < 5; pair++) {
            rows.add(List.of(callback("Language " + pair + "a", "la" + pair),
                    callback("Language " + pair + "b", "lb" + pair)));
        }

        rows.add(List.of(callback("←", "lp:0"), callback("1/4", "np"), callback("→", "lp:1")));
        rows.add(List.of(callback("Back", "bk")));

        List<ActionRow> components = DiscordKeyboardMapper.components(new InlineKeyboard(rows));
        assertThat(components).hasSizeLessThanOrEqualTo(DiscordKeyboardMapper.MAX_ACTION_ROWS);

        List<String> ids = new ArrayList<>();

        for (ActionRow row : components) {
            assertThat(buttons(row)).hasSizeLessThanOrEqualTo(DiscordKeyboardMapper.MAX_BUTTONS_IN_ROW);
            buttons(row).forEach(button -> ids.add(button.getCustomId()));
        }

        assertThat(ids).hasSize(14).contains("bk", "np", "lp:0", "lp:1");

    }

    /** JDA refuses a caption past eighty characters instead of clipping it, so we clip first. */
    @Test
    void aLongCaptionIsTruncatedToTheDiscordLimit() {

        List<ActionRow> rows = DiscordKeyboardMapper.components(new InlineKeyboard(List.of(
                List.of(callback("x".repeat(120), "sd")))));

        assertThat(buttons(rows.get(0)).get(0).getLabel())
                .hasSize(DiscordKeyboardMapper.MAX_LABEL)
                .endsWith("…");

    }

}
