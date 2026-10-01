package eu.neydev.birthday.core.webapp;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramInitDataValidatorTest {

    private static final String TOKEN = "123456:TEST-TOKEN";
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private String buildInitData(Map<String, String> pairs) {

        Map<String, String> sorted = new TreeMap<>(pairs);
        StringBuilder dataCheck = new StringBuilder();
        sorted.forEach((key, value) -> {

            if (dataCheck.length() > 0) {
                dataCheck.append('\n');
            }

            dataCheck.append(key).append('=').append(value);

        });

        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                TOKEN.getBytes(StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(hmac(secret,
                dataCheck.toString().getBytes(StandardCharsets.UTF_8)));

        StringBuilder query = new StringBuilder();
        pairs.forEach((key, value) -> query.append(url(key)).append('=').append(url(value)).append('&'));
        query.append("hash=").append(hash);

        return query.toString();

    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void acceptsValidInitData() {

        String initData = buildInitData(Map.of(
                "user", "{\"id\":42,\"first_name\":\"Ivan\",\"language_code\":\"ru\"}",
                "auth_date", Long.toString(NOW.getEpochSecond() - 60),
                "query_id", "AAF"));

        var validator = new TelegramInitDataValidator(TOKEN, java.time.Duration.ofHours(1));
        var user = validator.validate(initData, NOW);

        assertThat(user).isPresent();
        assertThat(user.get().id()).isEqualTo(42);
        assertThat(user.get().languageCode()).isEqualTo("ru");

    }

    @Test
    void rejectsTamperedPayload() {

        String initData = buildInitData(Map.of(
                "user", "{\"id\":42}",
                "auth_date", Long.toString(NOW.getEpochSecond() - 60)));
        String tampered = initData.replace("42", "43");
        var validator = new TelegramInitDataValidator(TOKEN, java.time.Duration.ofHours(1));
        assertThat(validator.validate(tampered, NOW)).isEmpty();

    }

    @Test
    void rejectsStaleAuthDate() {

        String initData = buildInitData(Map.of(
                "user", "{\"id\":42}",
                "auth_date", Long.toString(NOW.getEpochSecond() - 7200)));
        var validator = new TelegramInitDataValidator(TOKEN, java.time.Duration.ofHours(1));
        assertThat(validator.validate(initData, NOW)).isEmpty();

    }

    @Test
    void rejectsWrongToken() {

        String initData = buildInitData(Map.of(
                "user", "{\"id\":42}",
                "auth_date", Long.toString(NOW.getEpochSecond() - 60)));
        var validator = new TelegramInitDataValidator("another:token", java.time.Duration.ofHours(1));
        assertThat(validator.validate(initData, NOW)).isEmpty();

    }

}
