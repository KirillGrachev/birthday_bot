package eu.neydev.birthday.core.command.handlers;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.command.CommandHandler;
import eu.neydev.birthday.core.command.ReplyBuilder;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;

import java.util.List;
import java.util.Map;

/** Owner: hot reload of messages/keyboards from YAML. */
public record AdminHandlers(MessageBundleHolder holder, AppConfig config, ReplyBuilder replies) {

    public CommandHandler reload() {
        return interaction -> {

            var profile = interaction.profile();

            if (!config.ownerKeys().contains(profile.user().key())) {
                if (interaction.isCallback()) {
                    return List.of(replies.toast((IncomingUpdate.Callback) interaction.update(),
                            profile, "message.perm.not", Map.of()));
                }

                return List.of(replies.send(profile, "message.perm.not", Map.of(), replies.none()));
            }

            holder.reload();

            if (interaction.isCallback()) {
                return List.of(replies.toast((IncomingUpdate.Callback) interaction.update(),
                        profile, "message.reloaded", Map.of()));
            }

            return List.of(replies.send(profile, "message.reloaded", Map.of(), replies.none()));

        };
    }

}
