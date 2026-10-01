package eu.neydev.birthday.core.storage;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.domain.ReminderKind;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL storage contract. Takes the external URL from BIRTHDAY_PG_URL,
 * and in its absence brings up an embedded Postgres (zonky) - that is, the dialect
 * runs both in CI and on a local machine without external services.
 */
class JdbcStoragePostgresTest {

    private static EmbeddedPostgres embedded;
    private static AppConfig.Storage config;
    private JdbcStorage storage;

    @BeforeAll
    static void startPostgres() throws Exception {

        String url = System.getenv("BIRTHDAY_PG_URL");

        if (url != null && !url.isBlank()) {
            config = new AppConfig.Storage(AppConfig.Storage.Type.POSTGRES, "unused", url,
                    System.getenv().getOrDefault("BIRTHDAY_PG_USER", "birthday"),
                    System.getenv().getOrDefault("BIRTHDAY_PG_PASSWORD", "birthday"), 4);
            return;
        }

        try {
            embedded = EmbeddedPostgres.builder().start();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.abort(
                    "Embedded Postgres is unavailable in this environment: " + t.getMessage());
            return;
        }

        config = new AppConfig.Storage(AppConfig.Storage.Type.POSTGRES, "unused",
                embedded.getJdbcUrl("postgres", "postgres"), "postgres", "postgres", 4);

    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (embedded != null) {
            embedded.close();
        }
    }

    @BeforeEach
    void open() {
        storage = JdbcStorage.open(config);
        cleanup();
    }

    @AfterAll
    static void noop() {
    }

    @org.junit.jupiter.api.AfterEach
    void close() {
        cleanup();
        storage.close();
    }

    private void cleanup() {
        try (var connection = storage.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM profiles");
            statement.execute("DELETE FROM delivery_log");
        } catch (Exception ignored) {
        }
    }

    private Profile profile(String externalId, Instant nextReminderAt) {

        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        return new Profile(
                new PlatformUser(Platform.DISCORD, externalId),
                externalId,
                Locale.forLanguageTag("en"),
                ZoneId.of("UTC"),
                new BirthDate(3, 5, 1995),
                true,
                LocalTime.of(9, 30),
                nextReminderAt,
                now, now);

    }

    @Test
    void upsertFindDueAndDeliveryLogOnPostgres() {

        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        storage.profiles().save(profile("1", now.minusSeconds(60)));
        storage.profiles().save(profile("2", now.plusSeconds(3600)));
        storage.profiles().save(profile("1", now.minusSeconds(30)));

        var due = storage.profiles().findDue(now, 10);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).user().externalId()).isEqualTo("1");
        assertThat(due.get(0).nextReminderAt()).isEqualTo(now.minusSeconds(30));

        assertThat(storage.deliveryLog().markSent(due.get(0).user(),
                ReminderKind.COUNTDOWN, LocalDate.of(2026, 9, 30), now)).isTrue();
        assertThat(storage.deliveryLog().markSent(due.get(0).user(),
                ReminderKind.COUNTDOWN, LocalDate.of(2026, 9, 30), now)).isFalse();

        storage.profiles().delete(due.get(0).user());
        storage.deliveryLog().deleteFor(due.get(0).user());
        assertThat(storage.profiles().find(due.get(0).user())).isEmpty();
        assertThat(storage.deliveryLog().wasSent(due.get(0).user(),
                ReminderKind.COUNTDOWN, LocalDate.of(2026, 9, 30))).isFalse();

    }

    @Test
    void migrationsApplyOnPostgres() {
        assertThat(storage.schemaVersion()).isEqualTo(JdbcStorage.migrations().size());
    }

}
