package eu.neydev.birthday.core.i18n;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

/**
 * Date, time and timezone fragments the way a person writes them in their own language:
 * "11 февраля 2006 года", "February 11, 2006", "11. Februar 2006", "11 de febrero de 2006".
 *
 * <p>Why not {@code DateTimeFormatter.ofLocalizedDate(LONG)}: the CLDR long style ends the
 * Russian date with the era abbreviation ("11 февраля 2006 г."), and a sentence that ends
 * with such a date doubles the dot ("...2006 г.."). The per-language patterns below spell
 * the year out instead, so any punctuation may follow a formatted date safely.
 *
 * <p>Timezones get the same treatment in the opposite direction: the bare zone name
 * ("Москва") tells a person nothing about the offset, so the name is always paired with
 * it: "Москва (UTC+03:00)". Zones whose name already is an offset ("UTC+03:00") are kept
 * as they are instead of being doubled.
 */
public final class LocalizedFormats {

    /** Full date patterns per language; the year is spelled out, no era abbreviation. */
    private static final Map<String, String> DATE_FULL = Map.of(
            "ru", "d MMMM yyyy 'года'",
            "en", "MMMM d, yyyy",
            "de", "d. MMMM yyyy",
            "es", "d 'de' MMMM 'de' yyyy");

    /** Month and day only, for birth dates stored without a year. */
    private static final Map<String, String> DATE_MONTH_DAY = Map.of(
            "ru", "d MMMM",
            "en", "MMMM d",
            "de", "d. MMMM",
            "es", "d 'de' MMMM");

    private LocalizedFormats() {
    }

    /** "11 февраля 2006 года" / "February 11, 2006": a date that may end a sentence. */
    public static String dateFull(@NotNull LocalDate date, @NotNull Locale locale) {

        String pattern = DATE_FULL.get(locale.getLanguage());

        if (pattern == null) {
            return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale));
        }

        return date.format(DateTimeFormatter.ofPattern(pattern).withLocale(locale));

    }

    /** "11 февраля" / "February 11": a date without a year never shows a placeholder one. */
    public static String dateMonthDay(int month, int day, @NotNull Locale locale) {

        String pattern = DATE_MONTH_DAY.get(locale.getLanguage());
        LocalDate date = LocalDate.of(2004, month, day);

        if (pattern == null) {
            return date.format(DateTimeFormatter.ofPattern("d MMMM").withLocale(locale));
        }

        return date.format(DateTimeFormatter.ofPattern(pattern).withLocale(locale));

    }

    /** "12:00" / "12:00 PM": the wall clock in the reader's convention. */
    public static String time(@NotNull LocalTime time, @NotNull Locale locale) {
        return time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale));
    }

    /**
     * "Москва (UTC+03:00)" / "Moscow Time (UTC+03:00)": the zone name a person recognises,
     * paired with the offset that removes any ambiguity. A zone whose display name already
     * is an offset form keeps it alone.
     */
    public static String zone(@NotNull ZoneId zone, @NotNull Locale locale) {

        String name = zone.getDisplayName(TextStyle.FULL, locale);
        String offset = zoneOffset(zone, Instant.now());

        if (name.contains("UTC") || name.contains("GMT") || name.equals(zone.getId())) {
            return name;
        }

        return name + " (" + offset + ")";

    }

    /** "UTC+03:00", "UTC-03:30", "UTC+00:00": the current offset of the zone, whole minutes. */
    public static String zoneOffset(@NotNull ZoneId zone, @NotNull Instant instant) {

        int totalSeconds = zone.getRules().getOffset(instant).getTotalSeconds();
        int minutes = Math.abs(totalSeconds) / 60;
        String sign = totalSeconds < 0 ? "-" : "+";

        return String.format(Locale.ROOT, "UTC%s%02d:%02d", sign, minutes / 60, minutes % 60);

    }

}
