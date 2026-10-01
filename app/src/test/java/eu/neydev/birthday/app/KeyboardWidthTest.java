package eu.neydev.birthday.app;

import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every keyboard a phone can receive, in every shipped language: a row that pairs two
 * buttons must carry captions that survive a half-width button on a narrow screen.
 *
 * <p>The regression is a real one: "Отключить уведомления" reached phones as
 * "Отключить уведомл...", because the pair looked fine on a desktop client. The rule
 * is checked per rendered caption, so a translation that grows past the half-row
 * width moves to a full row by construction (see {@link MenuFactory#MAX_PAIR_CAPTION}).
 */
class KeyboardWidthTest {

    private static final String GITHUB = "https://github.com/example/repo";

    private static Set<String> shippedLanguages() {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "messages"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertPairsFit(String language, InlineKeyboard keyboard) {
        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
            if (row.size() > 1) {
                assertThat(row)
                        .as(language + " row " + row)
                        .allMatch(button -> button.label().length() <= MenuFactory.MAX_PAIR_CAPTION);
            }
        }
    }

    @Test
    void noPairedCaptionIsClippedOnAPhoneInAnyLanguage() {

        Set<String> languages = shippedLanguages();
        MessageBundleHolder holder = new MessageBundleHolder("messages", null, languages, "en");
        MenuFactory menus = new MenuFactory(holder, GITHUB);

        for (String language : languages) {

            Locale locale = Locale.forLanguageTag(language);

            for (boolean notifyEnabled : new boolean[] {true, false}) {
                assertPairsFit(language, menus.mainMenu(locale, notifyEnabled, null));
                assertPairsFit(language, menus.mainMenu(locale, notifyEnabled, "https://example.com/app"));
            }

            assertPairsFit(language, menus.settings(locale));
            assertPairsFit(language, menus.confirmDelete(locale));
            assertPairsFit(language, menus.daysSinceToggle(locale, true));
            assertPairsFit(language, menus.daysSinceToggle(locale, false));

            for (int page = 0; page < 4; page++) {
                assertPairsFit(language, menus.languages(locale, page));
            }

            for (int page = 0; page < 6; page++) {
                assertPairsFit(language, menus.zones(locale, page, ZoneId.of("Europe/Moscow")));
            }

        }

    }

}
