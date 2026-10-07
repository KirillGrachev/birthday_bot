package eu.neydev.birthday.platform.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.api.UpdateSink;
import eu.neydev.birthday.core.text.RichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thin Slack transport: Socket Mode (websocket outbound, no public HTTPS needed)
 * and executing outbound commands via {@link SlackApiClient}.
 * Event mapping and rendering live in {@link SlackEventMapper} and {@link SlackRenderers}.
 *
 * <p>Every envelope must be acked by envelope_id; interactive envelopes
 * acked immediately (Slack expects a reply within 3 seconds), the business reply comes
 * a separate message via the outbound dispatcher.
 */
public final class SlackAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String botToken;
    private final String appToken;
    private final SlackApiClient client;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private Thread supervisor;

    public SlackAdapter(String botToken, String appToken) {
        this(botToken, appToken, new SlackApiClient(botToken));
    }

    public SlackAdapter(String botToken, String appToken, String apiBase) {
        this(botToken, appToken, new SlackApiClient(botToken, apiBase));
    }

    private SlackAdapter(String botToken, String appToken, SlackApiClient client) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.client = client;
    }

    @Override
    public Platform platform() {
        return Platform.SLACK;
    }

    @Override
    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank() && appToken != null && !appToken.isBlank();
    }

    @Override
    public void start(PlatformContext context) {

        UpdateSink sink = context.instrumentedSink(Platform.SLACK);
        running.set(true);

        supervisor = Thread.ofVirtual().name("slack-supervisor").start(() -> supervise(sink, context));
        log.info("Slack: Socket Mode started");

    }

    private void supervise(UpdateSink sink, PlatformContext context) {

        long backoff = 1_000;

        while (running.get()) {

            try {
                connectAndPump(sink, context);
                backoff = 1_000;
            } catch (Exception e) {

                log.warn("Slack socket failure, retry in {} ms: {}", backoff, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60_000);

            }

        }

    }

    private void connectAndPump(UpdateSink sink, PlatformContext context) throws Exception {

        String url = client.openSocketUrl(appToken);
        CountDownLatch closed = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {

                buffer.append(data);

                if (last) {
                    String envelope = buffer.toString();
                    buffer.setLength(0);
                    context.health().beat(Platform.SLACK);
                    handleEnvelope(envelope, sink, webSocket);
                }

                webSocket.request(1);
                return null;

            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closed.countDown();
            }

        };

        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener).join();
        socket.set(ws);
        closed.await();

        if (running.get()) {
            throw new PlatformException("Slack: socket closed");
        }

    }

    private void handleEnvelope(String envelopeJson, UpdateSink sink, WebSocket ws) {

        try {

            JsonNode envelope = MAPPER.readTree(envelopeJson);
            String type = envelope.path("type").asText("");
            String envelopeId = envelope.path("envelope_id").asText(null);

            switch (type) {

                case "events_api" -> {
                    ack(ws, envelopeId, null);
                    SlackEventMapper.mapEvent(envelope.path("event")).ifPresent(sink::accept);
                }

                case "interactive" -> {
                    ack(ws, envelopeId, MAPPER.createObjectNode());
                    SlackEventMapper.mapInteractive(envelope.path("payload")).ifPresent(sink::accept);
                }

                default -> ack(ws, envelopeId, null);

            }

        } catch (Exception e) {
            log.warn("Slack: envelope not processed: {}", e.getMessage());
        }

    }

    private void ack(WebSocket ws, String envelopeId, JsonNode payload) {

        if (envelopeId == null || ws == null) {
            return;
        }

        ObjectNode node = MAPPER.createObjectNode();
        node.put("envelope_id", envelopeId);

        if (payload != null) {
            node.set("payload", payload);
        }

        ws.sendText(node.toString(), true);

    }

    @Override
    public void stop() {

        running.set(false);
        WebSocket ws = socket.get();

        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }

        if (supervisor != null) {
            supervisor.interrupt();
        }

    }

    @Override
    public void execute(OutboundMessage message) {

        switch (message) {

            case OutboundMessage.Send send -> client.call("chat.postMessage",
                    messageParams(send.chatId(), null, send.text(), send.keyboard()));

            case OutboundMessage.Edit edit -> {
                String[] parts = edit.messageId().split("\\|", 2);
                client.call("chat.update", messageParams(parts[0],
                        parts.length > 1 ? parts[1] : "0", edit.text(), edit.keyboard()));
            }

            case OutboundMessage.Delete delete -> {
                String[] parts = delete.messageId().split("\\|", 2);
                client.call("chat.delete", Map.of(
                        "channel", parts[0],
                        "ts", parts.length > 1 ? parts[1] : "0"));
            }

            case OutboundMessage.AnswerCallback answer -> {
                if (!answer.text().isEmpty()) {
                    client.call("chat.postEphemeral", Map.of(
                            "channel", answer.chatId(),
                            "user", answer.interactionId(),
                            "text", answer.text()));
                }
            }

        }

    }

    /**
     * The parameters of a chat.postMessage / chat.update call. The blocks go along only
     * when there is a keyboard: an empty blocks array is a payload Slack refuses, and a
     * message without buttons needs the text alone.
     */
    private static Map<String, String> messageParams(String channel, String ts, RichText text,
                                                     InlineKeyboard keyboard) {

        Map<String, String> params = new HashMap<>();
        params.put("channel", channel);
        params.put("text", SlackRenderers.mrkdwn(text));

        if (ts != null) {
            params.put("ts", ts);
        }

        ArrayNode blocks = SlackRenderers.blocks(keyboard);

        if (!blocks.isEmpty()) {
            params.put("blocks", blocks.toString());
        }

        return params;

    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
