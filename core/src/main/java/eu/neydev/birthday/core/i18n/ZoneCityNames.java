package eu.neydev.birthday.core.i18n;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * City names for the zone picker in the reader's own script: "Москва" for a Russian,
 * "მოსკოვი" for a Georgian, "Moskau" for a German. Every shipped language carries a
 * catalog file with the exonyms its speakers read on maps and tickets; a language
 * without an entry for a city falls back to the city part of the zone id.
 *
 * <p>Reference data, not copy: it lives in {@code zone-cities/*.yml} beside the
 * message bundles but outside them, so the key-parity gate of the messages stays
 * a gate on sentences, not on city names. A coverage test keeps the catalog's
 * language set in step with the message bundles.
 */
public record ZoneCityNames(MessageBundle bundle) {

    public static ZoneCityNames empty() {
        return new ZoneCityNames(MessageBundle.load("zone-cities", null, Set.of()));
    }

    public static ZoneCityNames load(@NotNull Set<String> languages) {
        return new ZoneCityNames(MessageBundle.loadPresent("zone-cities", null, languages));
    }

    /** "Europe/Moscow" becomes "Москва" in Russian and "Moscow" where uncatalogued. */
    public String city(@NotNull String zoneId, @NotNull Locale locale) {
        String city = zoneId.substring(zoneId.lastIndexOf('/') + 1).replace('_', ' ');
        return bundle.get(cityKey(zoneId), locale.getLanguage()).orElse(city);
    }

    private static String cityKey(String zoneId) {
        return zoneId.substring(zoneId.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

}
