package eu.neydev.birthday.platform.viber;

import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.api.UpdateSink;
import eu.neydev.birthday.core.http.WebhookHandler;
import eu.neydev.birthday.core.http.WebhookSecret;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A thin Viber adapter: webhook input with a shared secret, webhook registration
 * at start, executing outbound via {@link ViberApiClient}.
 * Mapping and keyboard are in separate classes.
 *
 * <p>Viber has no message editing: Edit deliberately degrades
 * into a new send.
 */
public final class ViberAdapter implements PlatformAdapter, WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ViberAdapter.class);
    private static final int MAX_WEBHOOK_BODY_BYTES = 512 * 1024;

    private final String publicUrl;
    private final WebhookSecret webhookSecret;
    private final ViberApiClient client;
    private final AtomicReference<UpdateSink> sink = new AtomicReference<>();

    public ViberAdapter(String token, String publicUrl, String secret) {
        this(token, publicUrl, secret, null);
    }

    public ViberAdapter(String token, String publicUrl, String secret, String apiBase) {
        this.publicUrl = publicUrl;
        this.webhookSecret = new WebhookSecret(secret);
        this.client = apiBase == null
                ? new ViberApiClient(token)
                : new ViberApiClient(token, apiBase);
    }

    @Override
    public Platform platform() {
        return Platform.VIBER;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String path() {
        return "/hooks/viber";
    }

    @Override
    public void start(PlatformContext context) {
        this.sink.set(context.instrumentedSink(Platform.VIBER));
        registerWebhook();
    }

    private void registerWebhook() {

        try {

            ObjectNode payload = client.mapper().createObjectNode();
            payload.put("url", webhookSecret.decorateUrl(publicUrl + path()));
            payload.putArray("event_types")
                    .add("message")
                    .add("conversation_started")
                    .add("subscribed")
                    .add("unsubscribed");

            client.call("set_webhook", payload);
            log.info("Viber: webhook registered at {}", publicUrl + path());

        } catch (RuntimeException e) {
            log.warn("Viber: set_webhook failed (you can set it manually): {}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        sink.set(null);
    }

    @Override
    public @NotNull WebhookResponse handle(@NotNull WebhookRequest request) {

        if (!webhookSecret.matches(request)) {
            return WebhookResponse.denied("{\"error\":\"unauthenticated\"}");
        }

        if (request.body().length() > MAX_WEBHOOK_BODY_BYTES) {
            return new WebhookResponse(413, "application/json", "{\"error\":\"too large\"}");
        }

        dispatch(request.body());
        return WebhookResponse.ok("{\"status\":\"ok\"}");

    }

    private void dispatch(String body) {

        UpdateSink current = sink.get();

        if (current == null) {
            return;
        }

        try {
            ViberEventMapper.mapEvent(client.mapper().readTree(body))
                    .ifPresent(current::accept);
        } catch (Exception e) {
            log.warn("Viber: could not parse webhook body: {}", e.getMessage());
        }

    }

    @Override
    public void execute(OutboundMessage message) {
        switch (message) {
            case OutboundMessage.Send send -> send(send);
            case OutboundMessage.Edit edit -> send(new OutboundMessage.Send(
                    edit.platform(), edit.chatId(), edit.text(), edit.keyboard(), false));
            case OutboundMessage.Delete ignored -> {}
            case OutboundMessage.AnswerCallback ignored -> {}
        }
    }

    private void send(OutboundMessage.Send send) {

        ObjectNode payload = client.mapper().createObjectNode();
        payload.put("receiver", send.chatId());
        payload.put("type", "text");
        payload.put("text", send.text().toPlainText());
        payload.put("min_api_version", 2);

        ObjectNode keyboard = ViberKeyboardMapper.map(send.keyboard());

        if (keyboard != null) {
            payload.set("keyboard", keyboard);
        }

        client.call("send_message", payload);

    }

}
