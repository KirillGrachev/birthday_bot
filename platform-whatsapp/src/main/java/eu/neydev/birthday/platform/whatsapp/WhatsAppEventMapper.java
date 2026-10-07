package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;

import java.util.Optional;

/**
 * Mapping WhatsApp webhook events into core events: text messages
 * and interactive replies. A pure function of JSON.
 */
public final class WhatsAppEventMapper {

    private WhatsAppEventMapper() {
    }

    public static Optional<IncomingUpdate> mapMessage(JsonNode message) {

        String from = message.path("from").asText("");

        if (from.isEmpty()) {
            return Optional.empty();
        }

        if (message.has("text")) {
            return Optional.of(new IncomingUpdate.TextMessage(
                    user(from),
                    from,
                    message.path("text").path("body").asText(""),
                    null));
        }

        if (message.has("interactive")) {

            JsonNode interactive = message.path("interactive");
            String actionId = replyId(interactive);

            if (actionId == null) {
                return Optional.empty();
            }

            return Optional.of(new IncomingUpdate.Callback(
                    user(from),
                    from,
                    actionId,
                    message.path("id").asText("0"),
                    from,
                    null));

        }

        return Optional.empty();

    }

    /**
     * The id we put on the control the reader pressed. A reply button arrives as
     * {@code button_reply}; a row of a list message arrives as {@code list_reply}, and as
     * {@code nfm_reply} on the clients that render lists through the newer flow. All three
     * carry the same id, so the core never learns which control it was.
     */
    private static String replyId(JsonNode interactive) {

        for (String field : new String[] {"button_reply", "list_reply", "nfm_reply"}) {

            if (interactive.hasNonNull(field)) {
                return interactive.path(field).path("id").asText(null);
            }

        }

        return null;

    }

    private static PlatformUser user(String phone) {
        return new PlatformUser(Platform.WHATSAPP, phone);
    }

}
