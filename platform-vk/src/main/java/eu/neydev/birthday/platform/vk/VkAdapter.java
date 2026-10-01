package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.api.StartupFailureProbe;
import eu.neydev.birthday.core.api.ConnectionProbe;
import eu.neydev.birthday.core.api.UpdateSink;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VK adapter: Bots Long Poll API (message_new / message_event) + sending via
 * messages.send / messages.edit / messages.sendMessageEventAnswer.
 *
 * <p>The long poll lives on its own virtual thread with reconnection
 * (exponential backoff): a dropped connection does not kill the bot.
 * Keyboards are inline with callback buttons; presses arrive as message_event
 * with the payload {@code {"a":"<actionId>"}}.
 *
 * <p>A permission error (VK 15 "no access to call this method", VK 5 "authorization
 * failed") is a configuration fault, not a network fault: retries cannot fix it.
 * The adapter prints the exact fix once, slows its retries down to
 * {@link #PERMISSION_RETRY_MILLIS} and reports the error through
 * {@link StartupFailureProbe}, so the operator sees the reason at startup
 * instead of an endless wall of warnings.
 */
public final class VkAdapter implements PlatformAdapter, StartupFailureProbe, ConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(VkAdapter.class);

    private static final long RETRY_INITIAL_MILLIS = 1_000;
    private static final long RETRY_MAX_MILLIS = 60_000;
    private static final long PERMISSION_RETRY_MILLIS = 300_000;

    /** VK 5 = user authorization failed, VK 15 = the token has no scope for the method. */
    private static final int ERROR_AUTHORIZATION = 5;
    private static final int ERROR_ACCESS_DENIED = 15;

    private final String token;
    private final String groupId;
    private final VkApiClient api;
    private final java.net.http.HttpClient pollClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean permissionHintPrinted = new AtomicBoolean(false);
    private volatile PlatformException fatalStartup;
    private Thread pollThread;

    public VkAdapter(String token, String groupId) {
        this(token, groupId, null);
    }

    /** Test hook: the VK API base URL (a local fake server). */
    public VkAdapter(String token, String groupId, String apiBase) {
        this.token = token;
        this.groupId = groupId;
        this.api = apiBase == null ? new VkApiClient(token) : new VkApiClient(token, apiBase);
    }

    @Override
    public Platform platform() {
        return Platform.VK;
    }

    @Override
    public boolean isEnabled() {
        return token != null && !token.isBlank() && groupId != null && !groupId.isBlank();
    }

    @Override
    public void start(PlatformContext context) {

        UpdateSink sink = context.instrumentedSink(Platform.VK);
        running.set(true);
        pollThread = Thread.ofVirtual().name("vk-longpoll").start(() -> pollLoop(sink, context));

        log.info("VK: bots long poll started (group {})", groupId);

    }

    @Override
    public void stop() {

        running.set(false);
        connected.set(false);

        if (pollThread != null) {
            pollThread.interrupt();
        }

    }

    private void pollLoop(UpdateSink sink, PlatformContext context) {

        long backoffMillis = RETRY_INITIAL_MILLIS;

        while (running.get()) {

            try {

                JsonNode server = api.call("groups.getLongPollServer", Map.of("group_id", groupId));
                String url = server.path("server").asText();
                String key = server.path("key").asText();
                long ts = server.path("ts").asLong();

                connected.set(true);
                permissionHintPrinted.set(false);
                fatalStartup = null;
                backoffMillis = RETRY_INITIAL_MILLIS;

                while (running.get()) {

                    context.health().beat(Platform.VK);
                    JsonNode poll = pollOnce(url, key, ts);

                    if (poll.has("ts")) {
                        ts = poll.get("ts").asLong();
                    }

                    if (poll.has("failed")) {
                        break;
                    }

                    for (JsonNode event : poll.path("updates")) {
                        mapEvent(event).ifPresent(sink::accept);
                    }

                }

            } catch (PlatformException e) {

                connected.set(false);
                backoffMillis = isPermissionDenial(e)
                        ? onPermissionDenied(e)
                        : onTransportFailure(e, backoffMillis);
                sleep(backoffMillis);

            } catch (RuntimeException e) {

                connected.set(false);
                backoffMillis = onTransportFailure(e, backoffMillis);
                sleep(backoffMillis);

            }

        }

    }

    /**
     * The token has no rights for the method: only the operator can fix it by
     * reissuing the token, retries change nothing. The hint is printed once per
     * failure streak, then the log stays quiet for {@link #PERMISSION_RETRY_MILLIS},
     * and the error is exposed via {@link #fatalStartupError()}.
     *
     * @return the pause before the next attempt
     */
    private long onPermissionDenied(PlatformException e) {

        fatalStartup = new PlatformException(
                "VK: the group token has no rights for the Bots Long Poll API (" + e.getMessage()
                        + "). " + permissionHint(), e, e.errorCode());

        if (permissionHintPrinted.compareAndSet(false, true)) {
            log.error("{}", fatalStartup.getMessage());
        } else {
            log.warn("VK: long poll still denied (VK {}), next attempt in {} s - "
                            + "the token rights have to be fixed, see the hint above",
                    e.errorCode(), PERMISSION_RETRY_MILLIS / 1000);
        }

        return PERMISSION_RETRY_MILLIS;

    }

    /**
     * A transport or API failure that a retry can heal: honest backoff
     * up to {@link #RETRY_MAX_MILLIS}.
     *
     * @return the pause before the next attempt
     */
    private long onTransportFailure(RuntimeException e, long currentMillis) {

        if (e instanceof PlatformException p && p.errorCode() > 0) {
            log.warn("VK long poll failure, retry in {} ms: {}", currentMillis, p.getMessage());
        } else {
            log.warn("VK long poll network failure, retry in {} ms: {}", currentMillis, e.getMessage());
        }

        return Math.min(currentMillis * 2, RETRY_MAX_MILLIS);

    }

    /** A fatal configuration error of the background connection or {@code null}. */
    @Override
    public @Nullable PlatformException fatalStartupError() {
        return fatalStartup;
    }

    @Override
    public boolean connected() {
        return connected.get();
    }

    /**
     * VK 5 (authorization failed) and VK 15 (no scope for the method) mean the token
     * itself is wrong or lacks rights - as opposed to network failures and floods.
     */
    static boolean isPermissionDenial(PlatformException e) {
        int code = e == null ? 0 : e.errorCode();
        return code == ERROR_AUTHORIZATION || code == ERROR_ACCESS_DENIED;
    }

    /** The operator's checklist for VK 5 / VK 15 on {@code groups.getLongPollServer}. */
    static String permissionHint() {
        return "Fix: VK group -> Manage -> Messages -> enabled; Long Poll API -> enabled "
                + "(API version 5.199, event types: message_new, message_reply, message_edit, "
                + "message_typing_state, message_allow, message_deny, message_event); "
                + "then issue a community token with the 'messages' right "
                + "(Manage -> VK API -> Access tokens) and put it into VK_GROUP_TOKEN "
                + "in config/.env. A user token (OAuth) will not work: "
                + "groups.getLongPollServer accepts a community token only.";
    }

    private JsonNode pollOnce(String serverUrl, String key, long ts) {

        String url = serverUrl + "?act=a_check&key=" + key + "&ts=" + ts + "&wait=25";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(35))
                .GET().build();

        try {
            var response = pollClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("VK long poll interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("VK long poll network: " + e.getMessage(), e);
        }

    }

    private java.util.Optional<IncomingUpdate> mapEvent(JsonNode event) {

        String type = event.path("type").asText();

        return switch (type) {
            case "message_new" -> {

                JsonNode object = event.path("object");

                // Groups on an API version below 5.103 keep the message flat
                // in "object"; newer versions nest it into "object.message".
                // Both shapes are mapped, otherwise updates drop silently.
                yield mapMessageNew(object.has("message") ? object.path("message") : object);

            }
            case "message_event" -> mapMessageEvent(event.path("object"));
            default -> {
                log.debug("VK: event type {} is not mapped", type);
                yield java.util.Optional.empty();
            }
        };

    }

    private java.util.Optional<IncomingUpdate> mapMessageNew(JsonNode message) {

        String text = message.path("text").asText("");

        if (text.isBlank() || message.path("payload").isTextual()) {
            log.debug("VK: message without text skipped (service or keyboard-only)");
            return java.util.Optional.empty();
        }

        long fromId = message.path("from_id").asLong();

        return java.util.Optional.of(new IncomingUpdate.TextMessage(
                new PlatformUser(Platform.VK, Long.toString(fromId)),
                chatId(message.path("peer_id").asLong()),
                text,
                null));

    }

    private java.util.Optional<IncomingUpdate> mapMessageEvent(JsonNode object) {

        JsonNode payload = object.path("payload");
        String actionId = payload.path("a").asText(null);

        if (actionId == null) {
            return java.util.Optional.empty();
        }

        long userId = object.path("user_id").asLong();

        return java.util.Optional.of(new IncomingUpdate.Callback(
                new PlatformUser(Platform.VK, Long.toString(userId)),
                chatId(object.path("peer_id").asLong()),
                actionId,
                String.valueOf(object.path("conversation_message_id").asLong()),
                object.path("event_id").asText(),
                null));

    }

    private static String chatId(long peerId) {
        return Long.toString(peerId);
    }

    private static long nativePeerId(String chatId) {
        return Long.parseLong(chatId);
    }

    @Override
    public void execute(OutboundMessage message) {

        switch (message) {

            case OutboundMessage.Send send -> {

                Map<String, String> params = new HashMap<>();

                params.put("peer_id", Long.toString(nativePeerId(send.chatId())));
                params.put("random_id", Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
                params.put("message", VkTextRenderer.render(send.text()));
                String keyboard = VkKeyboardMapper.map(send.keyboard());

                if (!keyboard.isEmpty()) {
                    params.put("keyboard", keyboard);
                }

                api.call("messages.send", params);

            }

            case OutboundMessage.Edit edit -> {

                Map<String, String> params = new HashMap<>();
                params.put("peer_id", Long.toString(nativePeerId(edit.chatId())));
                params.put("conversation_message_id", edit.messageId());
                params.put("message", VkTextRenderer.render(edit.text()));
                String keyboard = VkKeyboardMapper.map(edit.keyboard());

                if (!keyboard.isEmpty()) {
                    params.put("keyboard", keyboard);
                }

                api.call("messages.edit", params);

            }

            case OutboundMessage.Delete delete -> {
                api.call("messages.delete", Map.of(
                        "peer_id", Long.toString(nativePeerId(delete.chatId())),
                        "conversation_message_ids", delete.messageId()));
            }

            case OutboundMessage.AnswerCallback answer -> answerCallback(answer);

        }

    }

    /** Toast on press: VK snackbar; empty text = silent ack (send nothing). */
    private void answerCallback(OutboundMessage.AnswerCallback answer) {

        if (answer.text().isEmpty()) {
            return;
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var eventData = mapper.createObjectNode();
        eventData.put("type", "show_snackbar");
        eventData.put("text", answer.text());

        api.call("messages.sendMessageEventAnswer", Map.of(
                "event_id", answer.interactionId(),
                "user_id", Long.toString(nativePeerId(answer.chatId())),
                "peer_id", Long.toString(nativePeerId(answer.chatId())),
                "event_data", eventData.toString()));

    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
