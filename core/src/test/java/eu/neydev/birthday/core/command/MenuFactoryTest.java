package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.i18n.ZoneCityNames;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MenuFactoryTest {

    private static final Locale RU = Locale.forLanguageTag("ru");

    private MessageBundleHolder holder() {
        return new MessageBundleHolder("messages", null, Set.of("ru", "en"), "ru");
    }

    @Test
    void languagePickerFollowsConfiguredOrderAndNames() {

        AppConfig.Locale locale = new AppConfig.Locale("ru",
                new LinkedHashSet<>(List.of("en", "ru")),
                Map.of("ru", "Русский", "en", "English"));
        MenuFactory menus = new MenuFactory(holder(), null, locale);

        InlineKeyboard keyboard = menus.languages(RU, 0);

        assertThat(keyboard.rows()).hasSize(2);
        assertThat(keyboard.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::label)
                .containsExactly("English", "• Русский");
        assertThat(keyboard.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.LANG_PREFIX + "en", Actions.LANG_PREFIX + "ru");
        assertThat(keyboard.rows().get(1).get(0).actionId()).isEqualTo(Actions.BACK);

    }

    @Test
    void languageWithoutConfiguredNameFallsBackToTheCode() {

        AppConfig.Locale locale = new AppConfig.Locale("ru",
                new LinkedHashSet<>(List.of("ru", "en")));
        MenuFactory menus = new MenuFactory(holder(), null, locale);

        InlineKeyboard keyboard = menus.languages(RU, 0);

        assertThat(keyboard.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::label)
                .containsExactly("• ru", "en");

    }

    @Test
    void configuredLanguageWithoutMessageBundleIsSkipped() {

        AppConfig.Locale locale = new AppConfig.Locale("ru",
                new LinkedHashSet<>(List.of("ru", "de", "en")),
                Map.of("ru", "Русский", "de", "Deutsch", "en", "English"));
        MenuFactory menus = new MenuFactory(holder(), null, locale);

        InlineKeyboard keyboard = menus.languages(RU, 0);

        assertThat(keyboard.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.LANG_PREFIX + "ru", Actions.LANG_PREFIX + "en");

    }

    /** Twelve bundles live in {@code messages-pages}: two pages of the picker, ten and two. */
    private MenuFactory pagedMenus() {

        MessageBundleHolder holder = new MessageBundleHolder(
                "messages-pages", null,
                new LinkedHashSet<>(List.of("en", "ru", "de", "es", "fr", "it",
                        "pt", "pl", "nl", "sv", "fi", "da")),
                "en");

        return new MenuFactory(holder, null, new AppConfig.Locale("ru",
                new LinkedHashSet<>(List.of("en", "ru", "de", "es", "fr", "it",
                        "pt", "pl", "nl", "sv", "fi", "da")),
                Map.of("en", "English", "ru", "Русский")));

    }

    @Test
    void languagePickerPaginatesTenLanguagesPerPageWithNavigation() {

        MenuFactory menus = pagedMenus();

        InlineKeyboard first = menus.languages(RU, 0);
        // five rows of two languages, then the navigation row, then the way back
        assertThat(first.rows()).hasSize(7);
        assertThat(first.rows().get(5))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.NOOP, Actions.LANG_PAGE_PREFIX + 1);
        assertThat(first.rows().get(5).get(0).label()).isEqualTo("1/2");
        assertThat(first.rows().get(6).get(0).actionId()).isEqualTo(Actions.BACK);

        InlineKeyboard second = menus.languages(RU, 1);
        assertThat(second.rows()).hasSize(3);
        assertThat(second.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.LANG_PREFIX + "fi", Actions.LANG_PREFIX + "da");
        assertThat(second.rows().get(1))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.LANG_PAGE_PREFIX + 0, Actions.NOOP);

    }

    @Test
    void pickerMarksTheCurrentLanguageAndOpensOnItsPage() {

        MenuFactory menus = pagedMenus();

        InlineKeyboard first = menus.languages(RU, 0);
        assertThat(first.rows().get(0).get(1).label()).startsWith("• ");

        assertThat(menus.pageOf("ru")).isZero();
        assertThat(menus.pageOf("fi")).isOne();
        assertThat(menus.pageOf("zz")).isZero();
        // an out-of-range page clamps instead of rendering an empty keyboard
        assertThat(menus.languages(RU, 9).rows()).hasSize(3);

    }

    @Test
    void zonePickerPagesPopularZonesAndOffersManualInput() {

        MenuFactory menus = new MenuFactory(holder(), null);
        InlineKeyboard keyboard = menus.zones(RU, 0, ZoneId.of("Europe/Moscow"));

        // five rows of two zones, the navigation row, then manual input and back
        assertThat(keyboard.rows()).hasSize(7);
        assertThat(keyboard.rows().get(0).get(0).label()).startsWith("• ");
        assertThat(keyboard.rows().get(0).get(0).actionId())
                .isEqualTo(Actions.ZONE_PICK_PREFIX + "Europe/Moscow");
        assertThat(keyboard.rows().get(5))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.NOOP, Actions.ZONE_PAGE_PREFIX + 1);
        assertThat(keyboard.rows().get(6))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.ZONE_MANUAL, Actions.BACK);

        assertThat(menus.zonePageOf("Europe/Moscow")).isZero();
        assertThat(menus.zonePageOf("UTC")).isEqualTo(5);
        assertThat(menus.zonePageOf("Mars/Olympus_Mons")).isZero();
        assertThat(menus.zones(RU, 99, ZoneId.of("UTC")).rows()).hasSize(9);

    }

    @Test
    void zonePickerSpeaksTheReadersScriptForCataloguedCities() {

        MenuFactory menus = new MenuFactory(holder(), null,
                new AppConfig.Locale("ru", new LinkedHashSet<>(List.of("ru", "en"))),
                ZoneCityNames.load(Set.of("ru", "en")));

        InlineKeyboard russian = menus.zones(RU, 0, ZoneId.of("Europe/Moscow"));
        assertThat(russian.rows().get(0).get(0).label()).startsWith("• Москва UTC+3");

        InlineKeyboard english = menus.zones(Locale.ENGLISH, 0, ZoneId.of("Europe/Moscow"));
        assertThat(english.rows().get(0).get(0).label()).startsWith("• Moscow UTC+3");

    }

    @Test
    void settingsMenuOpensTheLanguagePickerInsteadOfDoingNothing() {

        MenuFactory menus = new MenuFactory(holder(), null);

        InlineKeyboard keyboard = menus.settings(RU);
        assertThat(keyboard.rows())
                .flatExtracting(row -> row)
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .contains(Actions.LANG_MENU)
                .doesNotContain(Actions.NOOP);

    }

    @Test
    void daysSinceToggleOffersTheOtherCountingMode() {

        MenuFactory menus = new MenuFactory(holder(), null);

        InlineKeyboard fromTotal = menus.daysSinceToggle(RU, false);
        InlineKeyboard fromLast = menus.daysSinceToggle(RU, true);

        assertThat(fromTotal.rows().get(0).get(0).actionId()).isEqualTo(Actions.DAYS_SINCE_LAST);
        assertThat(fromLast.rows().get(0).get(0).actionId()).isEqualTo(Actions.DAYS_SINCE);
        assertThat(fromTotal.rows().get(1).get(0).actionId()).isEqualTo(Actions.BACK);

    }

    @Test
    void mainMenuGivesTheNotifyToggleItsOwnRow() {

        MenuFactory menus = new MenuFactory(holder(), "https://github.com/example/repo");

        InlineKeyboard keyboard = menus.mainMenu(RU, true, null);

        assertThat(keyboard.rows().get(0))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.SET_DATE);
        assertThat(keyboard.rows().get(1))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.TOGGLE_NOTIFY);
        assertThat(keyboard.rows().get(2))
                .extracting(InlineKeyboard.KeyboardButton::actionId)
                .containsExactly(Actions.DAYS_TO, Actions.DAYS_SINCE);

    }

    /**
     * A shared row clips the wider caption in messenger clients: "Отключить уведомления"
     * reached users as "Отключить уведомл...". Two buttons per row only when both fit.
     */
    @Test
    void noRowPairsTwoCaptionsThatWouldBeClipped() {

        MenuFactory menus = new MenuFactory(holder(), "https://github.com/example/repo");

        for (boolean notifyEnabled : new boolean[] {true, false}) {

            InlineKeyboard keyboard = menus.mainMenu(RU, notifyEnabled, "https://example.com/app");

            for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
                if (row.size() > 1) {
                    assertThat(row)
                            .as("row " + row)
                            .allMatch(button -> button.label().length() <= MenuFactory.MAX_PAIR_CAPTION);
                }
            }
        }

    }

}
