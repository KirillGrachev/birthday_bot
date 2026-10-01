package eu.neydev.birthday.core.api;

import eu.neydev.birthday.core.text.RichText;
import eu.neydev.birthday.core.util.TraceId;
import org.jetbrains.annotations.NotNull;

/**
 * A normalized outbound stream. The transport platform is a TYPE FIELD, not a prefix
 * strings: routing in the dispatcher is compiled, not parsed from the chatId.
 * {@code chatId} is the platform's native dialog identifier without any prefixes.
 */
public sealed interface OutboundMessage {

    Platform platform();

    String chatId();

    /** Correlation id of a chain (update or reminder), for end-to-end logs. */
    String traceId();

    /** Send a new message. */
    record Send(@NotNull Platform platform,
                @NotNull String chatId,
                @NotNull RichText text,
                @NotNull InlineKeyboard keyboard,
                boolean silent,
                @NotNull String traceId) implements OutboundMessage {
        public Send(Platform platform, String chatId, RichText text,
                    InlineKeyboard keyboard, boolean silent) {
            this(platform, chatId, text, keyboard, silent, TraceId.currentOrNew());
        }

        public Send(Platform platform, String chatId, RichText text, InlineKeyboard keyboard) {
            this(platform, chatId, text, keyboard, false, TraceId.currentOrNew());
        }
    }

    /** Edit an existing message (menu navigation without spam). */
    record Edit(@NotNull Platform platform,
                @NotNull String chatId,
                @NotNull String messageId,
                @NotNull RichText text,
                @NotNull InlineKeyboard keyboard,
                @NotNull String traceId) implements OutboundMessage {
        public Edit(Platform platform, String chatId, String messageId,
                    RichText text, InlineKeyboard keyboard) {
            this(platform, chatId, messageId, text, keyboard, TraceId.currentOrNew());
        }
    }

    /** Delete a message (a service one, e.g. an input-prompt draft). */
    record Delete(@NotNull Platform platform,
                  @NotNull String chatId,
                  @NotNull String messageId,
                  @NotNull String traceId) implements OutboundMessage {
        public Delete(Platform platform, String chatId, String messageId) {
            this(platform, chatId, messageId, TraceId.currentOrNew());
        }
    }

    /**
     * Reply to a button press: a toast notification or clearing the "clock".
     * {@code interactionId} - the same token that arrived in {@link IncomingUpdate.Callback}.
     */
    record AnswerCallback(@NotNull Platform platform,
                          @NotNull String chatId,
                          @NotNull String interactionId,
                          @NotNull String text,
                          boolean showAlert,
                          @NotNull String traceId) implements OutboundMessage {
        public AnswerCallback(Platform platform, String chatId, String interactionId,
                              String text, boolean showAlert) {
            this(platform, chatId, interactionId, text, showAlert, TraceId.currentOrNew());
        }
    }

}
