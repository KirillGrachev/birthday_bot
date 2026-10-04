package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.i18n.ZoneCityNames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keyboards are built by the core from YAML captions (i18n) and platform-independent
 * actionId. Platforms only translate the structure - no platform-specific geometry
 * of buttons in the business logic.
 */
public record MenuFactory(MessageBundleHolder holder, @Nullable String githubUrl,
                          List<String> languages, Map<String, String> languageNames,
                          ZoneCityNames zoneCities) {

    public MenuFactory(MessageBundleHolder holder, @Nullable String githubUrl, AppConfig.Locale locale,
                       ZoneCityNames zoneCities) {
        this(holder, githubUrl, List.copyOf(locale.supported()), locale.displayNames(), zoneCities);
    }

    public MenuFactory(MessageBundleHolder holder, @Nullable String githubUrl, AppConfig.Locale locale) {
        this(holder, githubUrl, locale, ZoneCityNames.empty());
    }

    /** Shorthand without explicit locale config: bundle languages, codes as captions. */
    public MenuFactory(MessageBundleHolder holder, @Nullable String githubUrl) {
        this(holder, githubUrl, List.copyOf(holder.bundle().languages()), Map.of(),
                ZoneCityNames.empty());
    }

    /**
     * The widest caption that still fits a HALF-width button on a narrow phone: a phone
     * client shows about eighteen characters of a half row before clipping (a desktop
     * client shows more, which is how the clipping went unnoticed once). Sixteen leaves
     * a margin for wider scripts; anything longer gets a full row of its own.
     */
    public static final int MAX_PAIR_CAPTION = 16;

    /**
     * Two buttons share a row only when both captions survive a phone screen; otherwise
     * each gets a full row. Keyboards are built per language, so the rule is applied to
     * the rendered captions, not to a per-language allowlist.
     */
    private void addPair(List<List<InlineKeyboard.KeyboardButton>> rows,
                         InlineKeyboard.KeyboardButton left,
                         @Nullable InlineKeyboard.KeyboardButton right) {

        if (right != null && left.label().length() <= MAX_PAIR_CAPTION
                && right.label().length() <= MAX_PAIR_CAPTION) {
            rows.add(List.of(left, right));
            return;
        }

        rows.add(List.of(left));

        if (right != null) {
            rows.add(List.of(right));
        }

    }

    /** Main menu: the same items as competitors, plus subscription and web app. */
    public InlineKeyboard mainMenu(@NotNull Locale locale, boolean notifyEnabled,
                               @Nullable String webAppUrl) {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        // The date button and the notify toggle always get a full row: their captions
        // ("Отключить уведомления") are longer than a half row on any screen.
        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.set_date"), Actions.SET_DATE,
                        InlineKeyboard.KeyboardButton.Style.PRIMARY)));
        rows.add(List.of(
                InlineKeyboard.KeyboardButton.callback(
                        label(locale, notifyEnabled ? "button.notify_off" : "button.notify_on"),
                        Actions.TOGGLE_NOTIFY)));

        addPair(rows,
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.days_to"), Actions.DAYS_TO),
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.days_since"), Actions.DAYS_SINCE));
        addPair(rows,
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.settings"), Actions.SETTINGS),
                webAppUrl == null ? null
                        : InlineKeyboard.KeyboardButton.url(label(locale, "button.webapp"), webAppUrl));
        addPair(rows,
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.about"), Actions.ABOUT),
                githubUrl == null ? null
                        : InlineKeyboard.KeyboardButton.url(label(locale, "button.github"), githubUrl));

        return new InlineKeyboard(rows);

    }

    public InlineKeyboard about(@NotNull Locale locale) {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        if (githubUrl != null) {
            rows.add(List.of(InlineKeyboard.KeyboardButton.url(label(locale, "button.github"), githubUrl)));
        }

        rows.add(List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));
        return new InlineKeyboard(rows);

    }

    public InlineKeyboard settings(@NotNull Locale locale) {

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();
        addPair(rows,
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.time"), Actions.SET_TIME),
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.zone"), Actions.SET_ZONE));

        rows.add(List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.language"),
                Actions.LANG_MENU)));
        rows.add(List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.delete"), Actions.DELETE,
                InlineKeyboard.KeyboardButton.Style.DANGER)));
        rows.add(List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));

        return new InlineKeyboard(rows);

    }

    /** Languages per picker page: five rows of two, so thirty-five languages stay tappable. */
    public static final int LANGUAGES_PER_PAGE = 10;

    /** The bullet prefix that marks the language the profile already uses. */
    private static final String CURRENT_MARK = "• ";

    /**
     * Language picker: one button per configured language that actually has a
     * message bundle, captioned from {@code locale.display-names} (the code itself
     * when no caption is configured). The button order follows {@code locale.supported}.
     *
     * <p>The list is long enough to need pages: two columns of five, a navigation row
     * with the page counter, and the current language marked with a bullet. The picker
     * opens on the page that holds the reader's own language ({@link #pageOf}).
     */
    public InlineKeyboard languages(@NotNull Locale locale, int page) {

        List<String> available = availableLanguages();

        int pages = Math.max(1, (available.size() + LANGUAGES_PER_PAGE - 1) / LANGUAGES_PER_PAGE);
        int current = Math.clamp(page, 0, pages - 1);
        String currentLanguage = locale.getLanguage();

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboard.KeyboardButton> row = new ArrayList<>(2);

        for (int index = current * LANGUAGES_PER_PAGE;
             index < Math.min(available.size(), (current + 1) * LANGUAGES_PER_PAGE); index++) {

            String language = available.get(index);
            String caption = languageNames.getOrDefault(language, language);

            if (language.equals(currentLanguage)) {
                caption = CURRENT_MARK + caption;
            }

            row.add(InlineKeyboard.KeyboardButton.callback(caption, Actions.LANG_PREFIX + language));

            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>(2);
            }

        }

        if (!row.isEmpty()) {
            rows.add(row);
        }

        if (pages > 1) {
            rows.add(navigationRow(locale, current, pages, Actions.LANG_PAGE_PREFIX));
        }

        rows.add(List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"), Actions.BACK)));
        return new InlineKeyboard(rows);

    }

    /** The page that holds a language: the picker opens on the reader's own language. */
    public int pageOf(@NotNull String language) {
        int index = availableLanguages().indexOf(language);
        return index < 0 ? 0 : index / LANGUAGES_PER_PAGE;
    }

    /** Configured languages that actually ship a bundle, in the configured order. */
    public List<String> availableLanguages() {

        List<String> available = new ArrayList<>(languages.size());

        for (String language : languages) {
            if (holder.bundle().languages().contains(language)) {
                available.add(language);
            }
        }

        return available;

    }

    /** Previous / page counter / next. The counter is a dead button: tap-proof, readable. */
    private List<InlineKeyboard.KeyboardButton> navigationRow(Locale locale, int page, int pages,
                                                              String pagePrefix) {

        List<InlineKeyboard.KeyboardButton> row = new ArrayList<>(3);

        if (page > 0) {
            row.add(InlineKeyboard.KeyboardButton.callback(
                    label(locale, "button.page_prev"), pagePrefix + (page - 1)));
        }

        row.add(InlineKeyboard.KeyboardButton.callback(
                (page + 1) + "/" + pages, Actions.NOOP));

        if (page < pages - 1) {
            row.add(InlineKeyboard.KeyboardButton.callback(
                    label(locale, "button.page_next"), pagePrefix + (page + 1)));
        }

        return row;

    }

    /**
     * Zone picker: the popular zones of {@link ZoneCatalog} ten to a page, the reader's
     * current zone marked with a bullet, and a way out into the free-text prompt for
     * everything outside the list. Captions carry the city and the live offset, so a
     * half-row stays readable: "Moscow UTC+3", not "Europe/Moscow".
     */
    public InlineKeyboard zones(@NotNull Locale locale, int page, @NotNull ZoneId current) {

        List<String> available = ZoneCatalog.POPULAR;
        int pages = Math.max(1, (available.size() + LANGUAGES_PER_PAGE - 1) / LANGUAGES_PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);

        List<InlineKeyboard.KeyboardButton> pageButtons = new ArrayList<>(LANGUAGES_PER_PAGE);

        for (int index = shown * LANGUAGES_PER_PAGE;
             index < Math.min(available.size(), (shown + 1) * LANGUAGES_PER_PAGE); index++) {

            String zone = available.get(index);
            ZoneId id = ZoneId.of(zone);
            String caption = zoneCities.city(zone, locale) + " " + ZoneCatalog.offset(id);

            if (zone.equals(current.getId())) {
                caption = CURRENT_MARK + caption;
            }

            pageButtons.add(InlineKeyboard.KeyboardButton.callback(
                    caption, Actions.ZONE_PICK_PREFIX + zone));

        }

        List<List<InlineKeyboard.KeyboardButton>> rows = new ArrayList<>();

        for (int i = 0; i < pageButtons.size(); i += 2) {
            addPair(rows, pageButtons.get(i),
                    i + 1 < pageButtons.size() ? pageButtons.get(i + 1) : null);
        }

        if (pages > 1) {
            rows.add(navigationRow(locale, shown, pages, Actions.ZONE_PAGE_PREFIX));
        }

        addPair(rows,
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.zone_manual"),
                        Actions.ZONE_MANUAL),
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"), Actions.BACK));

        return new InlineKeyboard(rows);

    }

    /** The page that holds a zone, so the picker opens on the reader's own. */
    public int zonePageOf(@NotNull String zoneId) {
        int index = ZoneCatalog.POPULAR.indexOf(zoneId);
        return index < 0 ? 0 : index / LANGUAGES_PER_PAGE;
    }

    public InlineKeyboard confirmDelete(@NotNull Locale locale) {
        return new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.delete_yes"),
                        Actions.DELETE_YES, InlineKeyboard.KeyboardButton.Style.DANGER),
                        InlineKeyboard.KeyboardButton.callback(label(locale, "button.delete_no"),
                                Actions.DELETE_NO))));
    }

    /**
     * The "days since" answer screen: one toggle between the two counting modes (labelled
     * with the mode it switches TO) and the usual way back.
     */
    public InlineKeyboard daysSinceToggle(@NotNull Locale locale, boolean sinceLast) {

        String label = label(locale, sinceLast ? "button.days_since_total" : "button.days_since_last");
        String action = sinceLast ? Actions.DAYS_SINCE : Actions.DAYS_SINCE_LAST;

        return new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback(label, action)),
                List.of(InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"),
                        Actions.BACK))));

    }

    /**
     * The quiet keyboard for proactive messages: one button that summons the full menu
     * as a fresh message. A daily reminder is content, not navigation; the navigation
     * arrives when the reader asks for it.
     */
    public InlineKeyboard menuButton(@NotNull Locale locale) {
        return new InlineKeyboard(List.of(List.of(InlineKeyboard.KeyboardButton.callback(
                label(locale, "button.menu"), Actions.MENU_OPEN))));
    }

    public InlineKeyboard back(@NotNull Locale locale) {
        return new InlineKeyboard(List.of(List.of(
                InlineKeyboard.KeyboardButton.callback(label(locale, "button.back"), Actions.BACK))));
    }

    public InlineKeyboard none() {
        return InlineKeyboard.empty();
    }

    private String label(Locale locale, String key) {
        return holder.renderer().raw(key, locale, Map.of());
    }

}
