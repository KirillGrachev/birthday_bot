package eu.neydev.birthday.core.domain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-tolerant parsing of a birthdate, reminder time and time zone.
 *
 * <p>Dates: ISO, {@code dd.mm.yyyy}, {@code dd-mm-yyyy}, {@code dd/mm/yyyy},
 * "5 марта 1998", "5 марта", "March 5, 1998", "March 5" - month names are understood
 * in the user's profile language first and in every other shipped language after it:
 * a Russian typing "11 Февраля 2006" into an English profile is a date, not an error.
 * The year is optional.
 *
 * <p>Time: {@code 18}, {@code 18:30}, {@code 8:05}, {@code 0805}.
 * Zone: {@code Europe/Moscow}, {@code UTC+3}, aliases {@code msk}, {@code spb}.
 */
public final class DateParsers {

    /** Numeric patterns with and without a year; 'u' = proleptic year for strict validation. */
    private static final List<String> NUMERIC_PATTERNS = List.of(
            "uuuu-MM-dd", "dd.MM.uuuu", "dd-MM-uuuu", "dd/MM/uuuu",
            "dd.MM", "dd-MM", "dd/MM"
    );

    private static final List<String> MONTH_NAME_PATTERNS = List.of(
            "d MMMM uuuu", "d MMM uuuu", "d MMMM", "d MMM",
            "MMMM d, uuuu", "MMM d, uuuu", "MMMM d", "MMM d"
    );

    private static final Pattern NUMERIC = Pattern.compile(
            "^(\\d{1,2})[.\\-/](\\d{1,2})(?:[.\\-/](\\d{2}|\\d{4}))?$");

    private static final Map<String, String> ZONE_ALIASES = Map.of(
            "msk", "Europe/Moscow",
            "spb", "Europe/Moscow",
            "utc", "UTC",
            "gmt", "UTC",
            "msk+0", "Europe/Moscow"
    );

    private static final Pattern TIME = Pattern.compile(
            "^(?:([01]?\\d|2[0-3])(?::([0-5]\\d))?|([01]\\d|2[0-3])([0-5]\\d))$");

    private DateParsers() {
    }

    /**
     * The oldest believable birth year. The newest one is not a constant: nobody is born
     * in the future, so the upper bound is the reader's today, passed in by the caller.
     */
    private static final int MIN_YEAR = 1900;

    /**
     * A birthdate parse with its failure reason: either a date, or the out-of-range year
     * that made the parse refuse, or nothing at all when the input is not a date. The
     * caller owes the user a different sentence for "I cannot read this" and "I can read
     * it, but 1889 is not a birth year".
     */
    public record BirthDateParse(@Nullable BirthDate date, @Nullable Integer yearOutOfRange) {

        public boolean parsed() {
            return date != null;
        }

        public Optional<BirthDate> result() {
            return Optional.ofNullable(date);
        }
    }

    /**
     * Parsing a birthdate. Returns {@link Optional#empty()} instead of an exception:
     * the caller decides which friendly message to show.
     */
    public static Optional<BirthDate> parseBirthDate(@NotNull String raw, @NotNull Locale locale) {
        return parseBirthDateDetailed(raw, locale).result();
    }

    public static BirthDateParse parseBirthDateDetailed(@NotNull String raw, @NotNull Locale locale) {
        return parseBirthDateDetailed(raw, locale, LocalDate.now(ZoneOffset.UTC));
    }

    /** The detailed parse with an explicit today: the upper bound of a birthdate. */
    public static BirthDateParse parseBirthDateDetailed(@NotNull String raw, @NotNull Locale locale,
                                                        @NotNull LocalDate today) {
        return parseInLocale(raw, locale, today);
    }

    /**
     * The same, but month names are tried in every shipped language after the profile's
     * own: people type the month the way they think of it, not the way their profile is
     * configured. The profile language goes first, the rest in a sorted order so a
     * parse never depends on iteration luck.
     */
    public static BirthDateParse parseBirthDateInAnyLanguage(@NotNull String raw,
                                                             @NotNull Locale preferred,
                                                             @NotNull Collection<String> languages) {
        return parseBirthDateInAnyLanguage(raw, preferred, languages, LocalDate.now(ZoneOffset.UTC));
    }

    public static BirthDateParse parseBirthDateInAnyLanguage(@NotNull String raw,
                                                             @NotNull Locale preferred,
                                                             @NotNull Collection<String> languages,
                                                             @NotNull LocalDate today) {

        BirthDateParse own = parseInLocale(raw, preferred, today);

        if (own.parsed() || own.yearOutOfRange() != null) {
            return own;
        }

        for (String language : new TreeSet<>(languages)) {

            if (language.equals(preferred.getLanguage())) {
                continue;
            }

            BirthDateParse parsed = parseInLocale(raw, Locale.forLanguageTag(language), today);

            if (parsed.parsed() || parsed.yearOutOfRange() != null) {
                return parsed;
            }

        }

        return new BirthDateParse(null, null);

    }

    private static BirthDateParse parseInLocale(String raw, Locale locale, LocalDate today) {

        String input = raw.trim().toLowerCase(locale);

        if (input.isEmpty()) {
            return new BirthDateParse(null, null);
        }

        Matcher numeric = NUMERIC.matcher(input);

        if (numeric.matches()) {

            int day = Integer.parseInt(numeric.group(1));
            int month = Integer.parseInt(numeric.group(2));
            Integer year = parseYear(numeric.group(3));

            return tryBirthDate(month, day, year, today);

        }

        for (String pattern : NUMERIC_PATTERNS) {
            BirthDateParse parsed = tryFormatter(input, pattern, locale, true, today);
            if (parsed.parsed() || parsed.yearOutOfRange() != null) return parsed;
        }

        for (String pattern : MONTH_NAME_PATTERNS) {

            BirthDateParse parsed = tryFormatter(input, pattern, locale, false, today);
            if (parsed.parsed() || parsed.yearOutOfRange() != null) return parsed;

        }

        return new BirthDateParse(null, null);

    }

    private static BirthDateParse tryFormatter(String input, String pattern,
                                               Locale locale, boolean numericOnly, LocalDate today) {

        try {

            DateTimeFormatter formatter = new java.time.format.DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale)
                    .withResolverStyle(ResolverStyle.STRICT);

            var parsed = formatter.parse(input);
            int month = parsed.get(java.time.temporal.ChronoField.MONTH_OF_YEAR);
            int day = parsed.get(java.time.temporal.ChronoField.DAY_OF_MONTH);
            Integer year = parsed.isSupported(java.time.temporal.ChronoField.YEAR)
                    ? parsed.get(java.time.temporal.ChronoField.YEAR)
                    : null;

            if (numericOnly && pattern.contains("MMM")) {
                return new BirthDateParse(null, null);
            }

            return tryBirthDate(month, day, year, today);

        } catch (DateTimeException e) {
            return new BirthDateParse(null, null);
        }
    }

    private static BirthDateParse tryBirthDate(int month, int day, @Nullable Integer year,
                                               LocalDate today) {
        try {

            BirthDate date = new BirthDate(month, day, year);

            if (year != null && (year < MIN_YEAR || date.requireFullDate().isAfter(today))) {

                // A readable date with an impossible year: a typo, or a birthday
                // from a future that has not happened yet.
                return new BirthDateParse(null, year);
            }

            return new BirthDateParse(date, null);

        } catch (RuntimeException e) {
            return new BirthDateParse(null, null);
        }
    }

    private static @Nullable Integer parseYear(@Nullable String group) {

        if (group == null) return null;

        int year = Integer.parseInt(group);
        return group.length() == 2 ? 1900 + year : year;

    }

    /** Parsing the reminder time. */
    public static Optional<LocalTime> parseTime(@NotNull String raw) {

        Matcher matcher = TIME.matcher(raw.trim());

        if (!matcher.matches()) {
            return Optional.empty();
        }

        if (matcher.group(1) != null) {

            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));

            return Optional.of(LocalTime.of(hour, minute));

        }

        return Optional.of(LocalTime.of(Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4))));

    }

    /** Parsing a time zone with aliases and the UTC+3 / UTC-05:30 form. */
    public static Optional<ZoneId> parseZone(@NotNull String raw) {

        String input = raw.trim();

        if (input.isEmpty()) {
            return Optional.empty();
        }

        String alias = ZONE_ALIASES.get(input.toLowerCase(Locale.ROOT));

        if (alias != null) {
            return Optional.of(ZoneId.of(alias));
        }

        try {
            return Optional.of(ZoneId.of(input));
        } catch (DateTimeException e) {
            return Optional.empty();
        }

    }

}
