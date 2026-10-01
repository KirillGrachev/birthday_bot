package eu.neydev.birthday.core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.LocalDate;
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
        assertThat(DateParsers.parseBirthDateInAnyLanguage("11 Февраля 2006", Locale.ENGLISH, shipped).result())
                .contains(new BirthDate(2, 11, 2006));
        // and the mirror case: an English month typed into a Russian profile
        assertThat(DateParsers.parseBirthDateInAnyLanguage("March 5, 1998", RU, shipped).result())
                .contains(new BirthDate(3, 5, 1998));
        // a Greek and a Kazakh month reach the same place
        assertThat(DateParsers.parseBirthDateInAnyLanguage("5 Μαρτίου 1998", Locale.ENGLISH, shipped).result())
                .contains(new BirthDate(3, 5, 1998));
        assertThat(DateParsers.parseBirthDateInAnyLanguage("5 наурыз 1998", Locale.ENGLISH, shipped).result())
                .contains(new BirthDate(3, 5, 1998));
        // numeric input never needed a language and still does not
        assertThat(DateParsers.parseBirthDateInAnyLanguage("05.03.1998", Locale.ENGLISH, shipped).result())
                .contains(new BirthDate(3, 5, 1998));
        // and nonsense stays nonsense in all of them
        assertThat(DateParsers.parseBirthDateInAnyLanguage("hello spring 1998", Locale.ENGLISH, shipped).result())
                .isEmpty();

    }

    @Test
    void anImpossibleYearGetsItsOwnReasonInsteadOfAGenericFailure() {

        DateParsers.BirthDateParse legacy = DateParsers.parseBirthDateDetailed("20.04.1889", RU);
        assertThat(legacy.parsed()).isFalse();
        assertThat(legacy.yearOutOfRange()).isEqualTo(1889);

        DateParsers.BirthDateParse future = DateParsers.parseBirthDateDetailed("20.04.2101", RU);
        assertThat(future.yearOutOfRange()).isEqualTo(2101);

        // month-name forms go through the same window, in any shipped language
        DateParsers.BirthDateParse named = DateParsers.parseBirthDateDetailed("5 марта 1889", RU);
        assertThat(named.yearOutOfRange()).isEqualTo(1889);

        DateParsers.BirthDateParse fine = DateParsers.parseBirthDateDetailed("20.04.1998", RU);
        assertThat(fine.parsed()).isTrue();
        assertThat(fine.yearOutOfRange()).isNull();

    }

    @Test
    void theUpperBoundIsTodayNotAConstant() {

        LocalDate today = LocalDate.of(2026, 10, 1);

        // December of the reader's own year is still the future: nobody is born there
        DateParsers.BirthDateParse future =
                DateParsers.parseBirthDateDetailed("05.12.2026", RU, today);
        assertThat(future.parsed()).isFalse();
        assertThat(future.yearOutOfRange()).isEqualTo(2026);

        // the same date becomes a birthday once today reaches it
        DateParsers.BirthDateParse present =
                DateParsers.parseBirthDateDetailed("05.12.2026", RU, LocalDate.of(2026, 12, 5));
        assertThat(present.parsed()).isTrue();
        assertThat(present.result()).contains(new BirthDate(12, 5, 2026));

        // and 1900 stays the floor
        DateParsers.BirthDateParse old =
                DateParsers.parseBirthDateDetailed("01.01.1899", RU, today);
        assertThat(old.yearOutOfRange()).isEqualTo(1899);

    }
}
