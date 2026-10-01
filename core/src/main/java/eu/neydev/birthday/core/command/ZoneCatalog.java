package eu.neydev.birthday.core.command;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The popular timezones offered as buttons: one page per ten, the rest of the
 * IANA database stays reachable through the manual input (the zone prompt and
 * the Mini App dropdown both accept any zone id).
 *
 * <p>The order follows the language picker: the communities the bot serves most
 * come first, so the first page is the one most people need.
 */
public final class ZoneCatalog {

    public static final List<String> POPULAR = List.of(
            "Europe/Moscow", "Europe/Kyiv", "Europe/Minsk", "Europe/Warsaw", "Europe/Berlin",
            "Europe/Paris", "Europe/Madrid", "Europe/Rome", "Europe/London", "Europe/Prague",
            "Europe/Bratislava", "Europe/Sofia", "Europe/Zagreb", "Europe/Ljubljana", "Europe/Amsterdam",
            "Europe/Copenhagen", "Europe/Stockholm", "Europe/Helsinki", "Europe/Tallinn", "Europe/Riga",
            "Europe/Vilnius", "Europe/Bucharest", "Europe/Budapest", "Europe/Athens", "Europe/Dublin",
            "Europe/Malta", "Europe/Istanbul", "Asia/Almaty", "Asia/Bishkek", "Asia/Dushanbe",
            "Asia/Ashgabat", "Asia/Tashkent", "Asia/Baku", "Asia/Yerevan", "Asia/Tbilisi",
            "Asia/Yekaterinburg", "Asia/Novosibirsk", "Asia/Vladivostok", "Asia/Dubai", "Asia/Jerusalem",
            "Asia/Kolkata", "Asia/Bangkok", "Asia/Singapore", "Asia/Tokyo", "Asia/Seoul",
            "Australia/Sydney", "Pacific/Auckland", "America/New_York", "America/Chicago", "America/Denver",
            "America/Los_Angeles", "America/Mexico_City", "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
            "Africa/Cairo", "Africa/Johannesburg", "UTC");

    private ZoneCatalog() {
    }

    /**
     * A button caption a half-row can hold: the city plus the current offset,
     * "Moscow UTC+3", "Kolkata UTC+5:30". The offset is read at render time and
     * is display-only: a DST shift changes a caption, never a stored zone.
     */
    /** "UTC+3", "UTC-5", "UTC+5:30": the current offset, half-hours included. */
    public static String offset(@NotNull ZoneId zone) {

        int totalSeconds = zone.getRules().getOffset(Instant.now()).getTotalSeconds();
        int minutes = Math.abs(totalSeconds) / 60;
        String sign = totalSeconds < 0 ? "-" : "+";
        String tail = minutes % 60 == 0 ? "" : ":" + String.format(Locale.ROOT, "%02d", minutes % 60);

        return "UTC" + sign + minutes / 60 + tail;

    }

    /** The short display name of the zone in the reader's language, for wide screens. */
    public static String displayName(@NotNull ZoneId zone, @NotNull Locale locale) {
        return zone.getDisplayName(TextStyle.SHORT, locale);
    }

}
