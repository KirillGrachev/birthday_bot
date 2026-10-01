package eu.neydev.birthday.core.i18n;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user-visible fragments of every sentence that carries a date, a clock or a zone.
 * These are the regression net for two production complaints: a Russian date that ended
 * with the era abbreviation doubled the dot of the sentence ("...2006 г.."), and a bare
 * zone name ("Москва") that never told the reader which offset the schedule uses.
 */
class LocalizedFormatsTest {

    private static final LocalDate DATE = LocalDate.of(2006, 2, 11);
    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    void fullDateSpellsTheYearOutInEveryShippedLanguage() {

        assertThat(LocalizedFormats.dateFull(DATE, Locale.forLanguageTag("ru")))
                .isEqualTo("11 февраля 2006 года");
        assertThat(LocalizedFormats.dateFull(DATE, Locale.forLanguageTag("en")))
                .isEqualTo("February 11, 2006");
        assertThat(LocalizedFormats.dateFull(DATE, Locale.forLanguageTag("de")))
                .isEqualTo("11. Februar 2006");
        assertThat(LocalizedFormats.dateFull(DATE, Locale.forLanguageTag("es")))
                .isEqualTo("11 de febrero de 2006");

    }

    @Test
    void fullDateNeverEndsWithAnAbbreviationDot() {

        for (String language : java.util.List.of("ru", "en", "de", "es")) {

            String date = LocalizedFormats.dateFull(DATE, Locale.forLanguageTag(language));

            assertThat(date).as(language).doesNotEndWith(".");
            assertThat(date + ".").as(language + " in a sentence").doesNotContain("..");

        }

    }

    @Test
    void aDateWithoutYearKeepsMonthAndDayOnly() {

        assertThat(LocalizedFormats.dateMonthDay(2, 11, Locale.forLanguageTag("ru")))
                .isEqualTo("11 февраля");
        assertThat(LocalizedFormats.dateMonthDay(2, 11, Locale.forLanguageTag("en")))
                .isEqualTo("February 11");
        assertThat(LocalizedFormats.dateMonthDay(2, 11, Locale.forLanguageTag("de")))
                .isEqualTo("11. Februar");
        assertThat(LocalizedFormats.dateMonthDay(2, 11, Locale.forLanguageTag("es")))
                .isEqualTo("11 de febrero");

    }

    @Test
    void wallClockFollowsTheReaderConvention() {

        assertThat(LocalizedFormats.time(LocalTime.of(12, 0), Locale.forLanguageTag("ru")))
                .isEqualTo("12:00");
        assertThat(LocalizedFormats.time(LocalTime.of(12, 0), Locale.forLanguageTag("de")))
                .isEqualTo("12:00");
        assertThat(LocalizedFormats.time(LocalTime.of(12, 0), Locale.forLanguageTag("en")))
                .contains("12:00");

    }

    @Test
    void zonePairsTheNameWithItsOffset() {

        ZoneId moscow = ZoneId.of("Europe/Moscow");

        assertThat(LocalizedFormats.zone(moscow, Locale.forLanguageTag("ru")))
                .isEqualTo("Москва (UTC+03:00)");
        assertThat(LocalizedFormats.zone(moscow, Locale.forLanguageTag("en")))
                .isEqualTo("Moscow Time (UTC+03:00)");

    }

    @Test
    void zoneWhoseNameAlreadyIsAnOffsetIsNotDoubled() {

        ZoneId fixed = ZoneId.of("UTC+3");

        assertThat(LocalizedFormats.zone(fixed, Locale.forLanguageTag("ru"))).isEqualTo("UTC+03:00");
        assertThat(LocalizedFormats.zone(fixed, Locale.forLanguageTag("en"))).isEqualTo("UTC+03:00");

    }

    @Test
    void negativeAndHalfHourOffsetsKeepTheirSignAndMinutes() {

        assertThat(LocalizedFormats.zoneOffset(ZoneId.of("America/St_Johns"), WINTER))
                .isEqualTo("UTC-03:30");
        assertThat(LocalizedFormats.zoneOffset(ZoneId.of("UTC"), WINTER))
                .isEqualTo("UTC+00:00");
        assertThat(LocalizedFormats.zoneOffset(ZoneId.of("Asia/Kathmandu"), WINTER))
                .isEqualTo("UTC+05:45");

    }

    @Test
    void anUnlistedLanguageFallsBackToTheJdkStyleInsteadOfFailing() {
        assertThat(LocalizedFormats.dateFull(DATE, Locale.FRENCH)).isEqualTo("11 février 2006");
    }

    @Test
    void zoneNamesFollowTheLanguageChosenInBotNotTheMessengerOne() {

        // The locale argument is the bot language (/lang, the picker, the Mini App):
        // a messenger profile in English with the bot switched to Russian gets
        // "Moskva" in Cyrillic, and the mirror case stays Latin.
        ZoneId moscow = ZoneId.of("Europe/Moscow");

        assertThat(LocalizedFormats.zone(moscow, Locale.forLanguageTag("ru")))
                .startsWith("Москва (UTC+03:00)");
        assertThat(LocalizedFormats.zone(moscow, Locale.forLanguageTag("kk")))
                .startsWith("Мәскеу уақыты (UTC+03:00)");
        assertThat(LocalizedFormats.zone(moscow, Locale.ENGLISH))
                .startsWith("Moscow Time (UTC+03:00)");

    }
}
