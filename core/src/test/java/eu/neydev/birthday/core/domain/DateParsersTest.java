package eu.neydev.birthday.core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DateParsersTest {

    private static final Locale RU = Locale.forLanguageTag("ru");

    @Test
    void parsesNumericWithYear() {
        assertThat(DateParsers.parseBirthDate("05.03.1998", RU))
                .contains(new BirthDate(3, 5, 1998));
    }

    @Test
    void parsesNumericWithoutYear() {
        assertThat(DateParsers.parseBirthDate("05.03", RU))
                .contains(new BirthDate(3, 5, null));
    }

    @Test
    void parsesIso() {
        assertThat(DateParsers.parseBirthDate("1998-03-05", RU))
                .contains(new BirthDate(3, 5, 1998));
    }

    @Test
    void parsesRussianMonthNames() {

        assertThat(DateParsers.parseBirthDate("5 марта 1998", RU))
                .contains(new BirthDate(3, 5, 1998));
        assertThat(DateParsers.parseBirthDate("5 марта", RU))
                .contains(new BirthDate(3, 5, null));

    }

    @Test
    void parsesEnglishMonthNames() {
        assertThat(DateParsers.parseBirthDate("March 5, 1998", Locale.ENGLISH))
                .contains(new BirthDate(3, 5, 1998));
    }

    @Test
    void rejectsInvalidDates() {

        assertThat(DateParsers.parseBirthDate("31.02.2000", RU)).isEmpty();
        assertThat(DateParsers.parseBirthDate("hello", RU)).isEmpty();
        assertThat(DateParsers.parseBirthDate("", RU)).isEmpty();
        assertThat(DateParsers.parseBirthDate("05.03.1899", RU)).isEmpty();

    }

    @Test
    void twoDigitYear() {
        assertThat(DateParsers.parseBirthDate("05.03.98", RU))
                .contains(new BirthDate(3, 5, 1998));
    }

    @Test
    void parsesTime() {

        assertThat(DateParsers.parseTime("12:00")).contains(LocalTime.of(12, 0));
        assertThat(DateParsers.parseTime("18")).contains(LocalTime.of(18, 0));
        assertThat(DateParsers.parseTime("8:05")).contains(LocalTime.of(8, 5));
        assertThat(DateParsers.parseTime("25:00")).isEmpty();

    }

    @Test
    void parsesZones() {

        assertThat(DateParsers.parseZone("Europe/Moscow")).contains(ZoneId.of("Europe/Moscow"));
        assertThat(DateParsers.parseZone("msk")).contains(ZoneId.of("Europe/Moscow"));
        assertThat(DateParsers.parseZone("UTC+3")).contains(ZoneId.of("UTC+3"));
        assertThat(DateParsers.parseZone("Mars/Olympus")).isEmpty();

    }

    @Test
    void monthNamesParseInEveryShippedLanguageNotOnlyTheProfileOne() {

        Set<String> shipped = Set.of("ru", "en", "de", "es", "el", "kk");

        // a Russian month typed into an English profile (the production complaint)
        assertThat(DateParsers.parseBirthDateInAnyLanguage("11 Февраля 2006", Locale.ENGLISH, shipped))
                .contains(new BirthDate(2, 11, 2006));
        // and the mirror case: an English month typed into a Russian profile
        assertThat(DateParsers.parseBirthDateInAnyLanguage("March 5, 1998", RU, shipped))
                .contains(new BirthDate(3, 5, 1998));
        // a Greek and a Kazakh month reach the same place
        assertThat(DateParsers.parseBirthDateInAnyLanguage("5 Μαρτίου 1998", Locale.ENGLISH, shipped))
                .contains(new BirthDate(3, 5, 1998));
        assertThat(DateParsers.parseBirthDateInAnyLanguage("5 наурыз 1998", Locale.ENGLISH, shipped))
                .contains(new BirthDate(3, 5, 1998));
        // numeric input never needed a language and still does not
        assertThat(DateParsers.parseBirthDateInAnyLanguage("05.03.1998", Locale.ENGLISH, shipped))
                .contains(new BirthDate(3, 5, 1998));
        // and nonsense stays nonsense in all of them
        assertThat(DateParsers.parseBirthDateInAnyLanguage("hello spring 1998", Locale.ENGLISH, shipped))
                .isEmpty();

    }
}
