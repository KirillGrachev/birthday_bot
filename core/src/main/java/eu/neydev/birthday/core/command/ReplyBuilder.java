package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.BirthdayMath;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.LocalizedFormats;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.text.RichText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A single point for building replies: texts from i18n, keyboards from MenuFactory,
 * placeholder parameters. All core outbound messages pass through this class,
 * so the platform type and chatId are set in one place.
 */
public record ReplyBuilder(MessageBundleHolder holder, MenuFactory menuFactory, AppConfig config) {

    public OutboundMessage send(Profile profile, String key, Map<String, Object> params,
                                InlineKeyboard keyboard) {
        return new OutboundMessage.Send(profile.user().platform(), profile.chatId(),
                rich(profile, key, params), keyboard);
    }

    public OutboundMessage edit(IncomingUpdate.Callback callback, Profile profile,
                                String key, Map<String, Object> params, InlineKeyboard keyboard) {
        return new OutboundMessage.Edit(profile.user().platform(), callback.chatId(),
                callback.messageId(), rich(profile, key, params), keyboard);
    }

    public OutboundMessage toast(IncomingUpdate.Callback callback, Profile profile,
                                 String key, Map<String, Object> params) {
        return new OutboundMessage.AnswerCallback(profile.user().platform(), callback.chatId(),
                callback.interactionId(), raw(profile, key, params), false);
    }

    public RichText rich(Profile profile, String key, Map<String, Object> params) {
        return holder.renderer().rich(key, profile.locale(), params);
    }

    public String raw(Profile profile, String key, Map<String, Object> params) {
        return holder.renderer().raw(key, profile.locale(), params);
    }

    public InlineKeyboard mainMenu(Profile profile) {
        return menuFactory.mainMenu(profile.locale(), profile.notifyEnabled(), webAppUrl());
    }

    public InlineKeyboard settings(Locale locale) {
        return menuFactory.settings(locale);
    }

    public InlineKeyboard languages(Locale locale, int page) {
        return menuFactory.languages(locale, page);
    }

    /** The picker page that holds a language, so the menu opens on the reader's own. */
    public int languagePage(String language) {
        return menuFactory.pageOf(language);
    }

    public InlineKeyboard zones(Locale locale, int page, ZoneId current) {
        return menuFactory.zones(locale, page, current);
    }

    /** The picker page that holds a zone, so the menu opens on the reader's own. */
    public int zonePage(String zoneId) {
        return menuFactory.zonePageOf(zoneId);
    }

    public InlineKeyboard back(Locale locale) {
        return menuFactory.back(locale);
    }

    public InlineKeyboard confirmDelete(Locale locale) {
        return menuFactory.confirmDelete(locale);
    }

    public InlineKeyboard about(Locale locale) {
        return menuFactory.about(locale);
    }

    public InlineKeyboard none() {
        return menuFactory.none();
    }

    public @Nullable String webAppUrl() {
        return config.webApp().enabled() ? config.webApp().publicUrl() : null;
    }

    public String daysToKey(Profile profile) {
        return profile.birthDate() == null ? "message.answer.not_set" : "message.answer.days_to";
    }

    /**
     * The "days since" answer key. The "since the last anniversary" mode needs only the
     * month and the day: the most recent anniversary is computable without a birth year,
     * so a yearless profile still gets a count instead of a refusal. Only the total mode
     * (days since the birthdate itself) truly requires the year.
     */
    public String daysSinceKey(Profile profile, boolean sinceLast) {

        if (profile.birthDate() == null) {
            return "message.answer.not_set";
        }

        if (sinceLast) {
            return "message.answer.days_since_last";
        }

        if (!profile.birthDate().hasYear()) {
            return "message.answer.no_year";
        }

        return "message.answer.days_since";

    }

    public Map<String, Object> daysSinceParams(Profile profile, boolean sinceLast) {

        if (profile.birthDate() == null) {
            return Map.of();
        }

        LocalDate today = profile.today();

        if (sinceLast) {
            // Month and day are enough: no year in the profile is not an obstacle here.
            return Map.of("days",
                    BirthdayMath.daysSinceLastAnniversary(profile.birthDate(), today, leapPolicy()));
        }

        if (!profile.birthDate().hasYear()) {
            return Map.of();
        }

        return Map.of(
                "days", BirthdayMath.daysSince(profile.birthDate(), today),
                "age", BirthdayMath.currentAge(profile.birthDate(), today, leapPolicy()));

    }

    /** The toggle between the two "days since" counting modes, labelled with the other mode. */
    public InlineKeyboard daysSinceToggle(Locale locale, boolean sinceLast) {
        return menuFactory.daysSinceToggle(locale, sinceLast);
    }

    public Map<String, Object> daysToParams(Profile profile) {

        if (profile.birthDate() == null) {
            return Map.of();
        }

        LocalDate today = profile.today();
        Map<String, Object> params = new HashMap<>();
        params.put("days", BirthdayMath.daysUntil(profile.birthDate(), today, leapPolicy()));
        params.put("date", LocalizedFormats.dateFull(
                BirthdayMath.nextAnniversary(profile.birthDate(), today, leapPolicy()),
                profile.locale()));

        params.putAll(scheduleParams(profile));

        return params;

    }

    /**
     * The answer to "when does the notification arrive": the wall clock and the zone of
     * the schedule, in the reader's own language. Every screen that promises a reminder
     * carries both, so nobody has to guess when the bot will write.
     *
     * <p>Deliberately without a date: screens that carry their own date (the saved birth
     * date, the next anniversary) add it under the {@code date} placeholder themselves,
     * and a shared date here would silently overwrite theirs.
     */
    private Map<String, Object> scheduleParams(Profile profile) {
        Locale locale = profile.locale();
        return Map.of(
                "time", LocalizedFormats.time(profile.notifyTime(), locale),
                "zone", LocalizedFormats.zone(profile.zone(), locale));
    }

    /** Parameters of the "date saved" screen: the saved date, the countdown and the schedule. */
    public Map<String, Object> dateSetParams(Profile profile, BirthDate date) {

        Map<String, Object> params = new HashMap<>();
        params.put("date", formatBirthDate(date, profile.locale()));
        params.put("days", BirthdayMath.daysUntil(date, profile.today(), leapPolicy()));
        params.putAll(scheduleParams(profile));

        return params;

    }

    /** Parameters of the "time saved" screen: the wall clock in the reader's convention. */
    public Map<String, Object> timeSetParams(Profile profile) {
        return Map.of("time", LocalizedFormats.time(profile.notifyTime(), profile.locale()));
    }

    /** Parameters of the "zone saved" screen: the zone name paired with its offset. */
    public Map<String, Object> zoneSetParams(Profile profile) {
        return Map.of("zone", LocalizedFormats.zone(profile.zone(), profile.locale()));
    }

    /** Parameters of the daily countdown reminder: how long is left and when the greeting lands. */
    public Map<String, Object> countdownParams(Profile profile, long days, LocalDate anniversary) {

        Map<String, Object> params = new HashMap<>();
        params.put("days", days);
        params.put("date", LocalizedFormats.dateFull(anniversary, profile.locale()));
        params.putAll(scheduleParams(profile));

        return params;

    }

    public Map<String, Object> settingsParams(Profile profile) {

        Locale locale = profile.locale();
        Map<String, Object> params = new HashMap<>();
        params.put("date", profile.birthDate() == null
                ? raw(profile, "message.settings.not_set", Map.of())
                : formatBirthDate(profile.birthDate(), locale));
        params.put("time", LocalizedFormats.time(profile.notifyTime(), locale));
        params.put("zone", LocalizedFormats.zone(profile.zone(), locale));
        params.put("notify", raw(profile,
                profile.notifyEnabled() ? "message.settings.on" : "message.settings.off", Map.of()));
        params.put("lang", languageName(locale));

        return params;

    }

    /**
     * "11 февраля 2006 г." / "February 11, 2006" in the user's own language. A date without
     * a year keeps month and day only, so the screen never shows a placeholder year.
     */
    private String formatBirthDate(@NotNull BirthDate birthDate, Locale locale) {
        if (!birthDate.hasYear())
            return LocalizedFormats.dateMonthDay(birthDate.month(), birthDate.day(), locale);
        return LocalizedFormats.dateFull(birthDate.requireFullDate(), locale);
    }

    /** The language in its own script and capitalized: "Русский", never the code "ru". */
    public String languageName(Locale locale) {

        String name = config.locale().displayNames()
                .getOrDefault(locale.getLanguage(), locale.getDisplayLanguage(locale));

        if (name == null || name.isEmpty()) {
            return locale.getLanguage();
        }

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);

    }

    public Map<String, Object> aboutParams() {
        String url = config.community().githubUrl();
        return Map.of("url", url != null ? url : "");
    }

    public eu.neydev.birthday.core.domain.LeapDayPolicy leapPolicy() {
        return config.scheduler().leapDayPolicy();
    }

    public @NotNull MessageBundleHolder holder() {
        return holder;
    }

}
