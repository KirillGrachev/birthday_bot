package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thin WhatsApp adapter: webhook input (Meta GET verification, shared-secret
 * and/or the X-Hub-Signature-256 signature) and executing outbound via
 * {@link WhatsAppApiClient}. Mapping and payload are in separate classes.
 *
 * <p>Platform limitations degrade deliberately: no editing/deleting
 * of a sent one (Edit -> a new send), no toasts (AnswerCallback -> no-op).
 */
public final class WhatsAppAdapter implements PlatformAdapter, WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAdapter.class);
    private static final int MAX_WEBHOOK_BODY_BYTES = 512 * 1024;

    private final String verifyToken;
    private final String appSecret;
    private final WebhookSecret webhookSecret;
    private final WhatsAppApiClient client;
    private final AtomicReference<UpdateSink> sink = new AtomicReference<>();

    public WhatsAppAdapter(String accessToken, String id, String secret) {
        this(accessToken, id, secret, null);
    }

    public WhatsAppAdapter(String accessToken, String id, String secret, String graphBase) {

        String[] parts = id == null ? new String[0] : id.split(":");
        String phoneId = parts.length > 0 ? parts[0] : "";

        this.verifyToken = parts.length > 1 ? parts[1] : "";
        this.appSecret = parts.length > 2 ? parts[2] : null;
        this.webhookSecret = new WebhookSecret(secret);
        this.client = graphBase == null
                ? new WhatsAppApiClient(accessToken, phoneId)
                : new WhatsAppApiClient(accessToken, phoneId, graphBase);

    }

    @Override
    public Platform platform() {
        return Platform.WHATSAPP;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String path() {
        return "/hooks/whatsapp";
    }

    @Override
    public void start(PlatformContext context) {
        this.sink.set(context.instrumentedSink(Platform.WHATSAPP));
        log.info("WhatsApp: webhook mounted at {} (secret={})", path(),
                webhookSecret.required() ? "on" : "signature-only");
    }

    @Override
    public void stop() {
        sink.set(null);
    }

    @Override
    public @NotNull WebhookResponse handle(@NotNull WebhookRequest request) {

        if ("GET".equalsIgnoreCase(request.method())) {
            return handleVerify(request);
        }

        if (!authenticated(request)) {
            return WebhookResponse.denied("{\"error\":\"unauthenticated\"}");
        }

        if (request.body().length() > MAX_WEBHOOK_BODY_BYTES) {
            return new WebhookResponse(413, "application/json", "{\"error\":\"too large\"}");
        }

        dispatch(request.body());
        return WebhookResponse.ok("{\"status\":\"ok\"}");

    }

    private WebhookResponse handleVerify(WebhookRequest request) {

        Map<String, String> query = parseQuery(request.query());
        boolean ok = "subscribe".equals(query.get("hub.mode"))
                && WebhookSecret.constantTimeEquals(verifyToken, query.get("hub.verify_token"));

        return ok
                ? WebhookResponse.plain(query.getOrDefault("hub.challenge", ""))
                : WebhookResponse.denied("{\"error\":\"verify failed\"}");

    }

    private boolean authenticated(WebhookRequest request) {

        boolean secretOk = webhookSecret.matches(request);
        boolean signatureOk = appSecret != null && signatureValid(request);

        return secretOk || signatureOk;

    }

    private boolean signatureValid(WebhookRequest request) {

        String header = request.headers().get("x-hub-signature-256");

        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of()
                    .formatHex(mac.doFinal(request.body().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }

    }

    private void dispatch(String body) {

        UpdateSink current = sink.get();

        if (current == null) {
            return;
        }

        try {

            JsonNode root = client.mapper().readTree(body);

            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    for (JsonNode message : change.path("value").path("messages")) {
                        WhatsAppEventMapper.mapMessage(message).ifPresent(current::accept);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("WhatsApp: could not parse webhook body: {}", e.getMessage());
        }

    }

    @Override
    public void execute(OutboundMessage message) {
        switch (message) {
            case OutboundMessage.Send send -> client.sendMessage(
                    WhatsAppPayloadBuilder.buildSend(send.chatId(), send.text(), send.keyboard()));
            case OutboundMessage.Edit edit -> client.sendMessage(
                    WhatsAppPayloadBuilder.buildSend(edit.chatId(), edit.text(), edit.keyboard()));
            case OutboundMessage.Delete ignored -> {}
            case OutboundMessage.AnswerCallback ignored -> {}
        }
    }

    private static Map<String, String> parseQuery(String query) {

        Map<String, String> result = new java.util.HashMap<>();

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq > 0) {
                result.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }

        }

        return result;

    }

}
