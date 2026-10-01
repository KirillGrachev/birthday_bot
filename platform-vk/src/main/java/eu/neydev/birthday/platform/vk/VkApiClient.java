package eu.neydev.birthday.platform.vk;

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
 * A minimal VK API client on JDK HttpClient: one POST method, strict parsing
 * of the reply and honest error classification (flood/limits -> RateLimited,
 * unreachable dialogs -> PermanentDelivery). Without a heavy SDK: a smaller surface
 * of failure and full control over timeouts.
 */
public final class VkApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://api.vk.com/method/";
    private static final String VERSION = "5.199";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String token;
    private final String baseUrl;

    public VkApiClient(String token) {
        this(token, API);
    }

    /** Test hook: the API base URL. */
    public VkApiClient(String token, String baseUrl) {

        this.token = token;
        this.baseUrl = baseUrl;

    }

    /** @return the {@code response} field of a successful response. */
    public JsonNode call(String method, Map<String, String> params) {

        StringBuilder body = new StringBuilder("access_token=")
                .append(URLEncoder.encode(token, StandardCharsets.UTF_8))
                .append("&v=").append(VERSION);
        params.forEach((key, value) -> body.append('&').append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + method))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("VK: call interrupted " + method, e);
        } catch (Exception e) {
            throw new PlatformException("VK: network " + method + ": " + e.getMessage(), e);
        }

        if (response.statusCode() == 429) {
            throw new PlatformException.RateLimitedException("VK HTTP 429", 1_000);
        }

        if (response.statusCode() >= 500) {
            throw new PlatformException("VK HTTP " + response.statusCode());
        }

        JsonNode root;

        try {
            root = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("VK: not JSON from " + method, e);
        }

        if (root.has("error")) {
            throw classify(root.get("error"), method);
        }

        return root.path("response");

    }

    private RuntimeException classify(JsonNode error, String method) {

        int code = error.path("error_code").asInt(0);
        String message = error.path("error_msg").asText("");

        switch (code) {

            case 6, 9, 29 -> {
                return new PlatformException.RateLimitedException("VK " + code + ": " + message, 1_000);
            }

            case 201, 204, 900, 901, 902 -> {
                return new PlatformException.PermanentDeliveryException("VK " + code + ": " + message);
            }

            default -> {
                return new PlatformException(
                        "VK API " + method + " error " + code + ": " + message, null, code);
            }

        }

    }

}
