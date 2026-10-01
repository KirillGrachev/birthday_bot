package eu.neydev.birthday.core.storage;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.domain.ReminderKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** A storage integration test on a REAL SQLite (WAL) in a temporary directory. */
class JdbcStorageTest {

    @TempDir
    Path tempDir;

    private JdbcStorage storage;

    @BeforeEach
    void open() {
        storage = JdbcStorage.open(new AppConfig.Storage(
                AppConfig.Storage.Type.SQLITE,
                tempDir.resolve("test.db").toString(),
                null, null, null, 4));
    }

    @AfterEach
    void close() {
        storage.close();
    }

    private Profile profile(String externalId, Instant nextReminderAt) {

        Instant now = Instant.parse("2026-09-30T10:00:00Z");
        return new Profile(
                new PlatformUser(Platform.TELEGRAM, externalId),
                externalId,
                Locale.forLanguageTag("ru"),
                ZoneId.of("UTC"),
                new BirthDate(6, 15, 1990),
                true,
                LocalTime.of(12, 0),
                nextReminderAt,
                now, now);

    }

    @Test
    void saveFindAndUpsert() {

        ProfileRepository repo = storage.profiles();
        Profile profile = profile("1", null);
        repo.save(profile);

        assertThat(repo.find(profile.user())).isPresent();

        repo.save(profile.withBirthDate(new BirthDate(1, 2, null), Instant.now()));
        Profile reloaded = repo.find(profile.user()).orElseThrow();
        assertThat(reloaded.birthDate()).isEqualTo(new BirthDate(1, 2, null));
        assertThat(repo.count()).isEqualTo(1);

    }

    @Test
    void findDueRespectsCursorAndLimit() {

        ProfileRepository repo = storage.profiles();
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        repo.save(profile("1", now.minusSeconds(60)));
        repo.save(profile("2", now.minusSeconds(30)));
        repo.save(profile("3", now.plusSeconds(3600)));
        repo.save(profile("4", null).withNotifyEnabled(false, now)
                .withNextReminderAt(now.minusSeconds(1), now));

        var due = repo.findDue(now, 10);
        assertThat(due).extracting(p -> p.user().externalId()).containsExactly("1", "2");

        assertThat(repo.findDue(now, 1)).extracting(p -> p.user().externalId())
                .containsExactly("1");

    }

    @Test
    void deliveryLogIsIdempotent() {

        DeliveryLogRepository log = storage.deliveryLog();
        PlatformUser user = new PlatformUser(Platform.TELEGRAM, "7");
        LocalDate date = LocalDate.of(2026, 9, 30);

        assertThat(log.markSent(user, ReminderKind.COUNTDOWN, date, Instant.now())).isTrue();
        assertThat(log.markSent(user, ReminderKind.COUNTDOWN, date, Instant.now())).isFalse();
        assertThat(log.wasSent(user, ReminderKind.COUNTDOWN, date)).isTrue();
        assertThat(log.wasSent(user, ReminderKind.BIRTHDAY, date)).isFalse();
        assertThat(log.markSent(user, ReminderKind.COUNTDOWN, date.plusDays(1), Instant.now())).isTrue();

    }

}
