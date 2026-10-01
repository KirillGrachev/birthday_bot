package eu.neydev.birthday.core.webapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Validation of Telegram Mini Apps {@code initData} per the official scheme:
 *
 * <pre>
 * secret_key = HMAC_SHA256(key = "WebAppData", data = botToken)
 * data_check_string = the initData pairs without the hash, sorted, joined by \n
 * sig = HMAC_SHA256(key = secret_key, data = data_check_string)
 * valid if hex(sig) == hash AND auth_date is fresh
 * </pre>
 *
 * <p>This lets the mini app authorize WITHOUT its own backend site:
 * the bot's server verifies the Telegram signature itself.
 */
public final class TelegramInitDataValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record WebAppUser(long id,
                             @Nullable String firstName,
                             @Nullable String lastName,
                             @Nullable String username,
                             @Nullable String languageCode) {
    }

    private final byte[] secretKey;
    private final Duration maxAge;

    public TelegramInitDataValidator(@NotNull String botToken, @NotNull Duration maxAge) {

        this.secretKey = hmacSha256("WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8));
        this.maxAge = maxAge;

    }

    /** @return the user data if the signature and expiry are valid. */
    public Optional<WebAppUser> validate(@NotNull String initData, @NotNull Instant now) {

        Map<String, String> pairs = parseQuery(initData);
        String hash = pairs.remove("hash");

        if (hash == null) {
            return Optional.empty();
        }

        String authDate = pairs.get("auth_date");

        if (authDate == null) {
            return Optional.empty();
        }

        try {
            Instant authInstant = Instant.ofEpochSecond(Long.parseLong(authDate));

            if (Duration.between(authInstant, now).abs().compareTo(maxAge) > 0) {
                return Optional.empty();
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        List<String> sorted = new ArrayList<>(pairs.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList());
        sorted.sort(Comparator.naturalOrder());
        String dataCheckString = String.join("\n", sorted);

        byte[] signature = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(signature);

        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        String userJson = pairs.get("user");

        if (userJson == null) {
            return Optional.empty();
        }

        try {

            JsonNode user = MAPPER.readTree(userJson);

            return Optional.of(new WebAppUser(
                    user.path("id").asLong(),
                    textOrNull(user, "first_name"),
                    textOrNull(user, "last_name"),
                    textOrNull(user, "username"),
                    textOrNull(user, "language_code")));

        } catch (Exception e) {
            return Optional.empty();
        }

    }

    private static Map<String, String> parseQuery(String initData) {

        Map<String, String> result = new TreeMap<>();

        for (String part : initData.split("&")) {

            if (part.isEmpty()) {
                continue;
            }

            int eq = part.indexOf('=');

            if (eq < 0) {
                continue;
            }

            result.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));

        }

        return result;

    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

}
