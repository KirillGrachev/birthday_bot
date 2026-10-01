package eu.neydev.birthday.platform.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.PlatformException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Slack API REST client: one HttpClient per instance, form-urlencoded bodies,
 * honest error classification (ratelimited -> RateLimited, dead channels ->
 * PermanentDelivery). Separate from transport and event mapping.
 */
public final class SlackApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "https://slack.com/api/";

    private final String botToken;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SlackApiClient(String botToken) {
        this(botToken, DEFAULT_BASE);
    }

    public SlackApiClient(String botToken, String apiBase) {

        this.botToken = botToken;
        this.apiBase = apiBase;

    }

    public JsonNode call(String method, Map<String, String> params) {
        return call(method, params, botToken);
    }

    public JsonNode call(String method, Map<String, String> params, String token) {

        StringBuilder body = new StringBuilder();
        params.forEach((key, value) -> body.append(body.length() > 0 ? "&" : "")
                .append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + method))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Slack: call interrupted " + method, e);
        } catch (Exception e) {
            throw new PlatformException("Slack: network " + method + ": " + e.getMessage(), e);
        }

        JsonNode json;

        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("Slack: not JSON from " + method, e);
        }

        if (!json.path("ok").asBoolean(false)) {
            throw classify(json.path("error").asText("unknown"), response);
        }

        return json;

    }

    public String openSocketUrl(String appToken) {

        JsonNode open = call("apps.connections.open", Map.of(), appToken);
        String url = open.path("url").asText(null);

        if (url == null) {
            throw new PlatformException("Slack: apps.connections.open without url");
        }

        return url;

    }

    private RuntimeException classify(String error, HttpResponse<String> response) {

        if ("ratelimited".equals(error)) {
            long retry = response.headers().firstValueAsLong("retry-after").orElse(1);
            return new PlatformException.RateLimitedException("Slack ratelimited", retry * 1000);
        }

        boolean permanent = switch (error) {
            case "channel_not_found", "not_in_channel", "is_archived", "user_not_found" -> true;
            default -> false;
        };

        if (permanent) {
            return new PlatformException.PermanentDeliveryException("Slack: " + error);
        }

        return new PlatformException("Slack error: " + error);

    }

}
