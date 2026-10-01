package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "days since" screen in both counting modes, including a yearless profile:
 * counting from the last anniversary needs only month and day, so the button
 * must answer instead of refusing.
 */
class ReplyBuilderDaysSinceTest {

    private final ReplyBuilder replies = builder();

    private static ReplyBuilder builder() {

        AppConfig.Scheduler scheduler = new AppConfig.Scheduler(
                Duration.ofSeconds(30), 100, 3, Duration.ofSeconds(20),
                AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);

        AppConfig config = new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE, "x", null, null, null, 1),
                new AppConfig.Community(null),
                scheduler,
                new AppConfig.Pipeline(10, 2, 50, 2, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 8080, null, Duration.ofHours(1)),
                new AppConfig.Locale("ru", Set.of("ru", "en")),
                Map.of(), List.of());

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en"), "ru");

        return new ReplyBuilder(holder, new MenuFactory(holder, null), config);

    }

    private static Profile profile(BirthDate date) {
        return new Profile(new PlatformUser(Platform.TELEGRAM, "1"), "1",
                Locale.forLanguageTag("ru"), ZoneId.of("UTC"), date, true,
                LocalTime.of(12, 0), null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void sinceLastModeAnswersWithoutAYear() {

        Profile profile = profile(BirthDate.ofMonthDay(3, 5));

        assertThat(replies.daysSinceKey(profile, true)).isEqualTo("message.answer.days_since_last");
        // today is 2026-09-30 in the profile's zone-free arithmetic: the test uses the
        // system date, so assert only that a days parameter arrived and is non-negative
        Map<String, Object> params = replies.daysSinceParams(profile, true);
        assertThat(params).containsKey("days");
        assertThat((long) params.get("days")).isNotNegative();

    }

    @Test
    void totalModeStillExplainsTheMissingYear() {

        Profile profile = profile(BirthDate.ofMonthDay(3, 5));

        assertThat(replies.daysSinceKey(profile, false)).isEqualTo("message.answer.no_year");
        assertThat(replies.daysSinceParams(profile, false)).isEmpty();

    }

    @Test
    void withAYearBothModesAnswerInFull() {

        Profile profile = profile(new BirthDate(3, 5, 2000));

        assertThat(replies.daysSinceKey(profile, true)).isEqualTo("message.answer.days_since_last");
        assertThat(replies.daysSinceKey(profile, false)).isEqualTo("message.answer.days_since");
        assertThat(replies.daysSinceParams(profile, false)).containsKeys("days", "age");

    }

    @Test
    void withoutADateBothModesAskToSetOne() {

        Profile profile = profile(null);

        assertThat(replies.daysSinceKey(profile, true)).isEqualTo("message.answer.not_set");
        assertThat(replies.daysSinceKey(profile, false)).isEqualTo("message.answer.not_set");

    }

}
