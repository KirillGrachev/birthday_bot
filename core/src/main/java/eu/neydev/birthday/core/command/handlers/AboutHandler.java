package eu.neydev.birthday.core.command.handlers;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.command.CommandHandler;
import eu.neydev.birthday.core.command.ReplyBuilder;

import java.util.List;

/** "About the project": open source, GitHub, star CTA and what it is all for. */
public record AboutHandler(ReplyBuilder replies) implements CommandHandler {

    @Override
    public List<eu.neydev.birthday.core.api.OutboundMessage> handle(
            eu.neydev.birthday.core.command.Interaction interaction) {

        var profile = interaction.profile();

        if (interaction.isCallback()) {
            return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                    "message.about", replies.aboutParams(), replies.about(profile.locale())));
        }

        return List.of(replies.send(profile, "message.about", replies.aboutParams(),
                replies.about(profile.locale())));

    }

}
