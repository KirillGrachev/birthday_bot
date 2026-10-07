package eu.neydev.birthday.platform.viber;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grid contract of a Viber keyboard. Viber has no rows of its own: it flows buttons
 * into six columns and starts a new visual row only when the next button does not fit.
 * A core row that leaves columns free therefore pulls the next row's first button up into
 * the gap, and the whole keyboard shifts - so every core row has to fill the grid exactly.
 */
class ViberKeyboardMapperTest {

    private static InlineKeyboard.KeyboardButton callback(String label, String id) {
        return InlineKeyboard.KeyboardButton.callback(label, id);
    }

    private static JsonNode buttons(InlineKeyboard keyboard) {
        return java.util.Objects.requireNonNull(ViberKeyboardMapper.map(keyboard)).path("Buttons");
    }

    @Test
    void everyRowSharesTheSixColumnsOutExactly() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int width = 1; width <= 6; width++) {

            List<InlineKeyboard.KeyboardButton> row = new ArrayList<>();

            for (int index = 0; index < width; index++) {
                row.add(callback("B" + width + index, "id" + width + index));
            }

            rows.add(row);

        }

        JsonNode buttons = buttons(new InlineKeyboard(rows));

        assertThat(buttons).hasSize(21);

        int at = 0;

        for (List<InlineKeyboard.KeyboardButton> row : rows) {

            int columns = 0;

            for (int index = 0; index < row.size(); index++) {

                JsonNode button = buttons.get(at + index);
                int width = button.path("Columns").asInt();

                assertThat(width).as("columns of a button").isBetween(1, 6);
                assertThat(button.path("Rows").asInt()).isEqualTo(1);
                assertThat(button.path("ActionType").asText()).isEqualTo("reply");
                assertThat(button.path("TrackingData").asText()).isEqualTo(row.get(index).actionId());
                columns += width;

            }

            assertThat(columns).as("the row fills the grid").isEqualTo(6);
            at += row.size();

        }

    }

    @Test
    void aRowOfFourDoesNotLeaveRoomForTheNextButton() {

        JsonNode buttons = buttons(new InlineKeyboard(List.of(
                List.of(callback("a", "a"), callback("b", "b"), callback("c", "c"),
                        callback("d", "d")),
                List.of(callback("e", "e")))));

        // 2+2+1+1 fills the row, so "e" cannot slide up into it
        assertThat(buttons.get(0).path("Columns").asInt()).isEqualTo(2);
        assertThat(buttons.get(1).path("Columns").asInt()).isEqualTo(2);
        assertThat(buttons.get(2).path("Columns").asInt()).isEqualTo(1);
        assertThat(buttons.get(3).path("Columns").asInt()).isEqualTo(1);
        assertThat(buttons.get(4).path("Columns").asInt()).isEqualTo(6);

    }

    @Test
    void aLinkButtonOpensAUrlAndACaptionIsClipped() {

        JsonNode buttons = buttons(new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.url("GitHub", "https://g.h"),
                        callback("x".repeat(80), "sd")))));

        assertThat(buttons.get(0).path("ActionType").asText()).isEqualTo("open-url");
        assertThat(buttons.get(0).path("ActionBody").asText()).isEqualTo("https://g.h");
        assertThat(buttons.get(0).path("TrackingData").isMissingNode()).isTrue();
        assertThat(buttons.get(1).path("Text").asText())
                .hasSize(ViberKeyboardMapper.LABEL_MAX)
                .endsWith("…");

    }

    /** Viber requires a non-empty Buttons array, so nothing to press means no keyboard. */
    @Test
    void anEmptyKeyboardMapsToNoKeyboardAtAll() {
        assertThat(ViberKeyboardMapper.map(InlineKeyboard.empty())).isNull();
    }

    /** Viber refuses a keyboard taller than twenty-four rows, so a taller one folds. */
    @Test
    void aKeyboardTallerThanTheGridLimitFolds() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int index = 0; index < 30; index++) {
            rows.add(List.of(callback("Zone " + index, "zp:" + index)));
        }

        JsonNode buttons = buttons(new InlineKeyboard(rows));

        // thirty singles fold into twenty-four rows of at most six buttons, and every
        // action survives: nothing is dropped to make the keyboard fit
        assertThat(gridRows(buttons)).isLessThanOrEqualTo(ViberKeyboardMapper.MAX_KEYBOARD_ROWS);
        assertThat(buttons).hasSize(30);

    }

    /** The number of visual rows Viber will paint, flowing the buttons into six columns. */
    private static int gridRows(JsonNode buttons) {

        int rows = 0;
        int used = 0;

        for (JsonNode button : buttons) {

            int columns = button.path("Columns").asInt();

            if (used + columns > 6) {
                rows++;
                used = 0;
            }

            used += columns;

        }

        return used == 0 ? rows : rows + 1;

    }

}
