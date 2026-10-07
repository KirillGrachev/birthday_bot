package eu.neydev.birthday.platform.discord;

import eu.neydev.birthday.core.api.ButtonLabels;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardFitter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.ArrayList;
import java.util.List;

/**
 * Core keyboard -> Discord components: rows become action rows, buttons keep their style,
 * a url button becomes a link button.
 *
 * <p>The geometry is Discord's own, and JDA enforces it before the request leaves the
 * process: five action rows a message, five buttons a row, eighty characters a label. A
 * picker page arrives with seven rows or more, so without folding the reader would get an
 * {@code IllegalArgumentException} instead of a menu. {@link KeyboardFitter} merges rows
 * until they fit and never drops a button.
 */
public final class DiscordKeyboardMapper {

    /** Discord stops a message at five action rows. */
    public static final int MAX_ACTION_ROWS = 5;

    /** An action row wider than five buttons is refused the same way. */
    public static final int MAX_BUTTONS_IN_ROW = 5;

    /** The label length Discord shows whole; longer ones it rejects, not clips. */
    public static final int MAX_LABEL = 80;

    private DiscordKeyboardMapper() {
    }

    public static List<ActionRow> components(InlineKeyboard keyboard) {

        List<ActionRow> rows = new ArrayList<>();

        for (List<InlineKeyboard.KeyboardButton> row
                : KeyboardFitter.fit(keyboard.rows(), MAX_ACTION_ROWS, MAX_BUTTONS_IN_ROW)) {

            List<Button> buttons = new ArrayList<>(row.size());

            for (InlineKeyboard.KeyboardButton button : row) {

                String label = ButtonLabels.truncate(button.label(), MAX_LABEL);

                if (button.url() != null) {
                    buttons.add(Button.link(button.url(), label));
                } else {
                    buttons.add(switch (button.style()) {
                        case PRIMARY -> Button.primary(button.actionId(), label);
                        case DANGER -> Button.danger(button.actionId(), label);
                        case SECONDARY -> Button.secondary(button.actionId(), label);
                    });
                }

            }

            rows.add(ActionRow.of(buttons));

        }

        return rows;

    }

}
