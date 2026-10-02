package eu.neydev.birthday.core.command.handlers;

import eu.neydev.birthday.core.command.CommandHandler;
import eu.neydev.birthday.core.command.ReplyBuilder;

import java.util.List;
import java.util.Map;

/** Menu navigation: start/help/back - the same screens from commands and buttons. */
public record MenuHandlers(ReplyBuilder replies) {

    public CommandHandler start() {
        return interaction -> List.of(replies.send(interaction.profile(), "message.start",
                Map.of(), replies.mainMenu(interaction.profile())));
    }

    public CommandHandler help() {
        return interaction -> List.of(replies.send(interaction.profile(), "message.help",
                Map.of(), replies.mainMenu(interaction.profile())));
    }

    /**
     * The quiet keyboard's button: the full menu arrives as a FRESH message, not an edit,
     * so the reminder text the reader was re-reading stays on the screen above it.
     */
    public CommandHandler openMenu() {
        return interaction -> List.of(replies.send(interaction.profile(), "message.start",
                Map.of(), replies.mainMenu(interaction.profile())));
    }

    public CommandHandler back() {
        return interaction -> {

            if (interaction.isCallback()) {
                return List.of(replies.edit((eu.neydev.birthday.core.api.IncomingUpdate.Callback)
                                interaction.update(), interaction.profile(),
                        "message.start", Map.of(), replies.mainMenu(interaction.profile())));
            }

            return List.of(replies.send(interaction.profile(), "message.start",
                    Map.of(), replies.mainMenu(interaction.profile())));

        };
    }

}
