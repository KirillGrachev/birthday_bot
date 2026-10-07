package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.PlatformException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * WhatsApp Cloud API (Graph) REST client: one HttpClient, JSON bodies,
 * classification of Meta errors (rate limit / permanent / transient).
 */
public final class WhatsAppApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "https://graph.facebook.com/v21.0/";

    private final String accessToken;
    private final String phoneId;
    private final String graphBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WhatsAppApiClient(String accessToken, String phoneId) {
        this(accessToken, phoneId, DEFAULT_BASE);
    }

    public WhatsAppApiClient(String accessToken, String phoneId, String graphBase) {

        this.accessToken = accessToken;
        this.phoneId = phoneId;
        this.graphBase = graphBase;

    }

    public void sendMessage(ObjectNode payload) {

        HttpRequest request = HttpRequest.newBuilder(URI.create(graphBase + phoneId + "/messages"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("WhatsApp: send interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: network: " + e.getMessage(), e);
        }

        JsonNode json;

        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: not JSON in response", e);
        }

        if (json.has("error")) {
            throw classify(json.path("error"));
        }

    }

    public ObjectMapper mapper() {
        return MAPPER;
    }

    private RuntimeException classify(JsonNode error) {

        int code = error.path("code").asInt(0);
        int subcode = error.path("error_subcode").asInt(0);

        if (code == 130429 || code == 4 || code == 17) {
            return new PlatformException.RateLimitedException("WhatsApp rate limit " + code, 2_000);
        }

        if (code == 100) {
            // "Invalid parameter": Meta read the payload and refused it, a row title too
            // long or a button too many. Retrying the same bytes cannot help, and the
            // chat itself is healthy, so this must not cost the reader their reminders.
            return new PlatformException.InvalidMessageException(
                    "WhatsApp 100: " + error.path("message").asText());
        }

        if (code == 131047 || code == 131049 || subcode == 2388088) {
            return new PlatformException.PermanentDeliveryException("WhatsApp: " + code);
        }

        return new PlatformException("WhatsApp error " + code + ": " + error.path("message").asText());

    }

}
