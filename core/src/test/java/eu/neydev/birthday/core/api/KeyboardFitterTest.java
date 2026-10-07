package eu.neydev.birthday.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folding rule every tight platform shares: rows merge until the keyboard fits, and
 * no button is lost on the way. A keyboard that cannot be folded is handed back unchanged,
 * because silently dropping an action would cost the reader a screen instead of a row.
 */
class KeyboardFitterTest {

    private static InlineKeyboard.KeyboardButton button(String label) {
        return InlineKeyboard.KeyboardButton.callback(label, "a:" + label);
    }

    private static List<InlineKeyboard.KeyboardButton> row(String... labels) {

        List<InlineKeyboard.KeyboardButton> buttons = new ArrayList<>();

        for (String label : labels) {
            buttons.add(button(label));
        }

        return buttons;

    }

    private static List<String> ids(List<List<InlineKeyboard.KeyboardButton>> rows) {
        return rows.stream().flatMap(List::stream)
                .map(InlineKeyboard.KeyboardButton::actionId).toList();
    }

    @Test
    void aKeyboardThatFitsKeepsItsShape() {

        List<List<InlineKeyboard.KeyboardButton>> rows =
                List.of(row("One"), row("Two"), row("Three"));

        assertThat(KeyboardFitter.fit(rows, 6, 5)).isSameAs(rows);

    }

    @Test
    void theBottomTwoRowsFoldFirstSoTheContentGridSurvives() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int pair = 0; pair < 5; pair++) {
            rows.add(row("L" + pair + "a", "L" + pair + "b"));
        }

        rows.add(row("prev", "1/4", "next"));
        rows.add(row("Back"));

        List<List<InlineKeyboard.KeyboardButton>> fitted = KeyboardFitter.fit(rows, 6, 5);

        assertThat(fitted).hasSize(6);
        // the page counter and the way back share the last row, the five pairs stay pairs
        assertThat(fitted.get(5)).hasSize(4);
        assertThat(fitted.subList(0, 5)).allSatisfy(pair -> assertThat(pair).hasSize(2));
        assertThat(ids(fitted)).containsExactlyInAnyOrderElementsOf(ids(rows));

    }

    @Test
    void singleButtonRowsRePairBeforeRowsOfAnyShape() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int index = 0; index < 7; index++) {
            rows.add(row("Zone " + index));
        }

        List<List<InlineKeyboard.KeyboardButton>> fitted = KeyboardFitter.fit(rows, 5, 5);

        assertThat(fitted).hasSizeLessThanOrEqualTo(5);
        assertThat(ids(fitted)).containsExactlyInAnyOrderElementsOf(ids(rows));

    }

    @Test
    void noRowEverGrowsPastTheWidthLimit() {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int index = 0; index < 13; index++) {
            rows.add(row("Long caption " + index));
        }

        rows.add(row("prev", "2/6", "next"));
        rows.add(row("Enter my own", "Back"));

        List<List<InlineKeyboard.KeyboardButton>> fitted = KeyboardFitter.fit(rows, 5, 5);

        assertThat(fitted).hasSize(5);
        assertThat(fitted).allSatisfy(row -> assertThat(row.size()).isLessThanOrEqualTo(5));
        assertThat(ids(fitted)).hasSize(18).containsExactlyInAnyOrderElementsOf(ids(rows));

    }

    @Test
    void anUnfoldableKeyboardIsReturnedWholeInsteadOfLosingButtons() {

        // Six rows of three: no two of them fit five buttons, so nothing can merge. The
        // platform will refuse this keyboard, and that refusal is the honest answer.
        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int index = 0; index < 6; index++) {
            rows.add(row("a" + index, "b" + index, "c" + index));
        }

        List<List<InlineKeyboard.KeyboardButton>> fitted = KeyboardFitter.fit(rows, 5, 5);

        assertThat(fitted).hasSize(6);
        assertThat(ids(fitted)).containsExactlyInAnyOrderElementsOf(ids(rows));

    }

    @Test
    void everyPlatformLimitIsPositiveAndATightPlatformPagesFewerChoices() {

        for (Platform platform : Platform.values()) {

            KeyboardLimits limits = KeyboardLimits.of(platform);

            assertThat(limits.maxRows()).as(platform + ": rows").isPositive();
            assertThat(limits.maxButtonsInRow()).as(platform + ": buttons in a row").isPositive();
            assertThat(limits.maxButtons()).as(platform + ": buttons in a keyboard").isPositive();
            assertThat(limits.pickerItemsPerPage()).as(platform + ": choices a page").isPositive();
            assertThat(limits.labelChars()).as(platform + ": caption").isPositive();

            // A count is never looser than the grid it is arranged in, and a platform
            // that publishes both agrees with itself.
            assertThat(limits.maxButtons()).as(platform + ": buttons against the grid")
                    .isLessThanOrEqualTo(limits.maxRows() * limits.maxButtonsInRow());

            // A picker page plus its navigation has to fit the platform's own budget,
            // otherwise the mapper would be folding away the reader's choices - and a
            // fold cannot shrink a count, which is the budget VK answers with.
            for (int chrome : new int[] {4, 5}) {

                assertThat(limits.pickerItems(chrome) + chrome)
                        .as(platform + ": a picker page with " + chrome + " chrome buttons")
                        .isLessThanOrEqualTo(limits.maxButtons())
                        .isLessThanOrEqualTo(limits.maxRows() * limits.maxButtonsInRow());

            }

        }

        assertThat(KeyboardLimits.of(Platform.WHATSAPP).pickerItemsPerPage())
                .as("WhatsApp pages fewer choices than Telegram")
                .isLessThan(KeyboardLimits.of(Platform.TELEGRAM).pickerItemsPerPage());

        // VK is the platform where the count bites rather than the rows: six rows of five
        // would hold thirty buttons, and an inline keyboard still stops at ten of them.
        KeyboardLimits vk = KeyboardLimits.of(Platform.VK);
        assertThat(vk.maxButtons()).as("VK counts buttons, not rows")
                .isLessThan(vk.maxRows() * vk.maxButtonsInRow());
        assertThat(vk.pickerItems(4)).as("VK pages fewer choices than it would by rows")
                .isLessThan(vk.pickerItemsPerPage());

    }

}
