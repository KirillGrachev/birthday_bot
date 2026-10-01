package eu.neydev.birthday.app;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.command.ReplyBuilder;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every screen that shows profile data must read like a sentence in the user's language,
 * not like a row of database values: a localized date, the zone name with its offset, the
 * language in its own script. Raw codes ("2006-02-11", "Europe/Moscow", "ru") were exactly
 * the complaint from production, and so was a date whose era abbreviation doubled the dot
 * of the sentence that carried it.
 */
class SettingsParamsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private ReplyBuilder replies() {

        AppConfig.Locale localeConfig = new AppConfig.Locale("ru",
                Set.of("ru", "en", "de", "es"),
                Map.of("ru", "Русский", "en", "English", "de", "Deutsch", "es", "Español"));
        AppConfig config = new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE, "x", null, null, null, 1),
                new AppConfig.Community("https://github.com/example/repo"),
                new AppConfig.Scheduler(Duration.ofSeconds(30), 100, 3, Duration.ofSeconds(20),
                        AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                        LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY),
                new AppConfig.Pipeline(10, 2, 50, 2, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 8080, null, Duration.ofHours(1)),
                localeConfig,
                Map.of(), java.util.List.of());

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en", "de", "es"), "ru");

        return new ReplyBuilder(holder, new MenuFactory(holder, null, localeConfig), config,
                Clock.fixed(NOW, ZoneId.of("UTC")));

    }

    private Profile profile(String language) {
        return new Profile(new PlatformUser(Platform.TELEGRAM, "1"), "1",
                Locale.forLanguageTag(language), ZoneId.of("Europe/Moscow"),
                new BirthDate(2, 11, 2006), true, LocalTime.of(12, 0),
                NOW, NOW, NOW);
    }

    @Test
    void russianSettingsReadLikeRussian() {

        Map<String, Object> params = replies().settingsParams(profile("ru"));

        assertThat(params)
                .containsEntry("date", "11 февраля 2006 года")
                .containsEntry("time", "12:00")
                .containsEntry("zone", "Москва (UTC+03:00)")
                .containsEntry("notify", "Включены")
                .containsEntry("lang", "Русский");

    }

    @Test
    void englishSettingsReadLikeEnglish() {

        Map<String, Object> params = replies().settingsParams(profile("en"));

        assertThat(params)
                .containsEntry("date", "February 11, 2006")
                .containsEntry("zone", "Moscow Time (UTC+03:00)")
                .containsEntry("notify", "On")
                .containsEntry("lang", "English");

    }

    @Test
    void aDateWithoutYearKeepsMonthAndDayOnly() {

        Profile noYear = new Profile(new PlatformUser(Platform.TELEGRAM, "1"), "1",
                Locale.forLanguageTag("ru"), ZoneId.of("Europe/Moscow"),
                BirthDate.ofMonthDay(2, 11), true, LocalTime.of(12, 0), NOW, NOW, NOW);

        assertThat(replies().settingsParams(noYear)).containsEntry("date", "11 февраля");

    }

    /**
     * The countdown answer must say when the greeting arrives: the date alone left users
     * wondering whether a notification was coming at all.
     */
    @Test
    void daysToAnswerCarriesTheWholeSchedule() {

        Map<String, Object> params = replies().daysToParams(profile("ru"));

        assertThat(params)
                .containsEntry("days", 133L)
                .containsEntry("date", "11 февраля 2027 года")
                .containsEntry("time", "12:00")
                .containsEntry("zone", "Москва (UTC+03:00)");

    }

    @Test
    void dateSetScreenRepeatsTheScheduleInReadableForm() {

        Map<String, Object> params = replies()
                .dateSetParams(profile("ru"), new BirthDate(2, 11, 2006));

        assertThat(params)
                .containsEntry("date", "11 февраля 2006 года")
                .containsEntry("days", 133L)
                .containsEntry("time", "12:00")
                .containsEntry("zone", "Москва (UTC+03:00)");

    }

    /**
     * The rendered sentence, not just its parameters: the zone value carries its own
     * parentheses, so a template that wraps {zone} in another pair prints
     * "(Москва (UTC+03:00))", and a date with an era abbreviation used to print "г..".
     */
    @Test
    void renderedCountdownAnswerReadsLikeASentence() {

        ReplyBuilder replies = replies();
        Profile profile = profile("ru");

        String rendered = replies.raw(profile, "message.answer.days_to", replies.daysToParams(profile));

        assertThat(rendered)
                .isEqualTo("До дня рождения осталось 133 дня. Поздравление придёт "
                        + "11 февраля 2027 года в 12:00 по поясу Москва (UTC+03:00).")
                .doesNotContain("((")
                .doesNotContain("..");

    }

    @Test
    void renderedDailyReminderCarriesTheSchedule() {

        ReplyBuilder replies = replies();
        Profile profile = profile("ru");

        String rendered = replies.raw(profile, "reminder.countdown",
                replies.countdownParams(profile, 133L, java.time.LocalDate.of(2027, 2, 11)));

        assertThat(rendered)
                .contains("До твоего дня рождения осталось 133 дня!")
                .contains("Поздравление придёт 11 февраля 2027 года в 12:00 по поясу Москва (UTC+03:00).");

    }

    @Test
    void timeAndZoneScreensShowLocalizedValues() {

        assertThat(replies().timeSetParams(profile("ru"))).containsEntry("time", "12:00");
        assertThat(replies().zoneSetParams(profile("ru")))
                .containsEntry("zone", "Москва (UTC+03:00)");

    }

}
