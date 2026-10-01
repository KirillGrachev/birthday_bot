package eu.neydev.birthday.core.domain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * Human-tolerant parsing of a birth date, reminder time and time zone.
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
     * Parsing a birth date. Returns {@link Optional#empty()} instead of an exception:
     * the caller decides which friendly message to show.
     */
    public static Optional<BirthDate> parseBirthDate(@NotNull String raw, @NotNull Locale locale) {

        return parseInLocale(raw, locale);

    }

    /**
     * The same, but month names are tried in every shipped language after the profile's
     * own: people type the month the way they think of it, not the way their profile is
     * configured. The profile language goes first, the rest in a sorted order so a
     * parse never depends on iteration luck.
     */
    public static Optional<BirthDate> parseBirthDateInAnyLanguage(@NotNull String raw,
                                                                  @NotNull Locale preferred,
                                                                  @NotNull Collection<String> languages) {

        Optional<BirthDate> own = parseInLocale(raw, preferred);

        if (own.isPresent()) {
            return own;
        }

        for (String language : new TreeSet<>(languages)) {

            if (language.equals(preferred.getLanguage())) {
                continue;
            }

            Optional<BirthDate> parsed = parseInLocale(raw, Locale.forLanguageTag(language));

            if (parsed.isPresent()) {
                return parsed;
            }

        }

        return Optional.empty();

    }

    private static Optional<BirthDate> parseInLocale(String raw, Locale locale) {

        String input = raw.trim().toLowerCase(locale);

        if (input.isEmpty()) {
            return Optional.empty();
        }

        Matcher numeric = NUMERIC.matcher(input);

        if (numeric.matches()) {

            int day = Integer.parseInt(numeric.group(1));
            int month = Integer.parseInt(numeric.group(2));
            Integer year = parseYear(numeric.group(3));

            if (year != null && (year < 1900 || year > 2100)) {
                return Optional.empty();
            }

            return tryBirthDate(month, day, year);

        }

        for (String pattern : NUMERIC_PATTERNS) {
            Optional<BirthDate> parsed = tryFormatter(input, pattern, locale, true);
            if (parsed.isPresent()) return parsed;
        }

        for (String pattern : MONTH_NAME_PATTERNS) {

            Optional<BirthDate> parsed = tryFormatter(input, pattern, locale, false);
            if (parsed.isPresent()) return parsed;

        }

        return Optional.empty();

    }

    private static Optional<BirthDate> tryFormatter(String input, String pattern,
                                                    Locale locale, boolean numericOnly) {

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
                return Optional.empty();
            }

            return tryBirthDate(month, day, year);

        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BirthDate> tryBirthDate(int month, int day, @Nullable Integer year) {
        try {
            return Optional.of(new BirthDate(month, day, year));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
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
