package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.domain.Profile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The context of a single user interaction: the event + the loaded profile.
 * Callbacks carry a message for Edit and a token for AnswerCallback - handlers
 * do not deal with unpacking platform types.
 */
public record Interaction(@NotNull IncomingUpdate update, @NotNull Profile profile) {

    public boolean isCallback() {
        return update instanceof IncomingUpdate.Callback;
    }

    /** Message id for Edit (menu navigation) or {@code null}. */
    public @Nullable String editMessageId() {
        return update instanceof IncomingUpdate.Callback callback ? callback.messageId() : null;
    }

    /** The button payload that produced this interaction, or {@code null} for typed text. */
    public @Nullable String actionId() {
        return update instanceof IncomingUpdate.Callback callback ? callback.actionId() : null;
    }

    public String text() {
        return update instanceof IncomingUpdate.TextMessage message ? message.text() : "";
    }

}
