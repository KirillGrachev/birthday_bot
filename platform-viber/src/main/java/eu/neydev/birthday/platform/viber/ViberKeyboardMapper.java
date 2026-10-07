package eu.neydev.birthday.platform.viber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.ButtonLabels;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardFitter;
import org.jetbrains.annotations.Nullable;

/**
 * Viber grid keyboard: six columns a row, the row's buttons sharing them out exactly.
 * Callback buttons carry the actionId in tracking_data.
 *
 * <p>The sharing out has to be exact. Viber does not know about our rows: it flows
 * buttons into the grid in the order they arrive and starts a new visual row only when
 * the next button does not fit the space left. A row of four buttons that took a column
 * each would leave two columns free, and the first button of the next row would slide up
 * into the gap - every row below it shifted by one. A keyboard is also capped at
 * twenty-four rows, which {@link KeyboardFitter} enforces before anything is sent.
 */
public final class ViberKeyboardMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The width of the grid: six columns, doubled to twenty-four in landscape. */
    static final int ROW_COLUMNS = 6;

    /** Viber refuses a keyboard taller than twenty-four rows. */
    static final int MAX_KEYBOARD_ROWS = 24;

    /** The caption length that still reads on a narrow button; Viber clips the rest. */
    static final int LABEL_MAX = 30;

    private ViberKeyboardMapper() {
    }

    /**
     * The keyboard of a message, or {@code null} when there is nothing to press: Viber
     * requires a non-empty {@code Buttons} array, so an empty keyboard is no keyboard.
     */
    public static @Nullable ObjectNode map(InlineKeyboard keyboard) {

        if (keyboard.isEmpty()) {
            return null;
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("Type", "keyboard");
        root.put("DefaultHeight", false);

        var buttons = root.putArray("Buttons");

        for (var row : KeyboardFitter.fit(keyboard.rows(), MAX_KEYBOARD_ROWS, ROW_COLUMNS)) {

            if (row.isEmpty()) {
                continue;
            }

            int[] widths = columnWidths(row.size());

            for (int index = 0; index < row.size(); index++) {

                InlineKeyboard.KeyboardButton button = row.get(index);
                ObjectNode node = buttons.addObject();

                if (button.url() != null) {
                    node.put("ActionType", "open-url");
                    node.put("ActionBody", button.url());
                } else {
                    node.put("ActionType", "reply");
                    node.put("ActionBody", button.actionId());
                    node.put("TrackingData", button.actionId());
                }

                node.put("Text", ButtonLabels.truncate(button.label(), LABEL_MAX));
                node.put("Columns", widths[index]);
                node.put("Rows", 1);

            }

        }

        return root;

    }

    /**
     * Six columns shared out among the buttons of a row, the remainder going to the first
     * ones: three buttons take two columns each, five take 2+1+1+1+1. The widths always
     * sum to exactly six, which is what keeps the next row where it belongs.
     */
    private static int[] columnWidths(int buttons) {

        int width = ROW_COLUMNS / buttons;
        int wider = ROW_COLUMNS % buttons;
        int[] columns = new int[buttons];

        for (int index = 0; index < buttons; index++) {
            columns[index] = width + (index < wider ? 1 : 0);
        }

        return columns;

    }

}
