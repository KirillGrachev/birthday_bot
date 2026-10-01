package eu.neydev.birthday.core.command;

import java.util.List;

/** A command/action handler: purely "event + profile -> outbound messages". */
@FunctionalInterface
public interface CommandHandler {

    List<eu.neydev.birthday.core.api.OutboundMessage> handle(Interaction interaction);

}
