package eu.neydev.birthday.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.i18n.ZoneCityNames;
import eu.neydev.birthday.platform.vk.VkKeyboardMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every keyboard the core builds, in every shipped language, seen through the VK
 * mapper: at most six inline rows of at most five buttons, and not one action lost
 * on the way. The gate is a production regression: VK rejected the language and
 * zone pickers whole with error 911 ("buttons contain too much rows"), because a
 * picker page is seven rows or more - Telegram renders that, VK does not accept it.
 */
class VkKeyboardRowsTest {

    private static final String GITHUB = "https://github.com/example/repo";
    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static void assertFitsVk(String what, InlineKeyboard keyboard) throws IOException {

        JsonNode rows = JSON.readTree(VkKeyboardMapper.map(keyboard)).path("buttons");
        assertThat(rows.size()).as(what + ": inline rows").isLessThanOrEqualTo(6);

        List<String> mapped = new ArrayList<>();

        for (JsonNode row : rows) {

            assertThat(row.size()).as(what + ": buttons in a row").isLessThanOrEqualTo(5);

            row.forEach(button -> mapped.add(
                    button.path("action").path("type").asText().equals("open_link")
                            ? button.path("action").path("link").asText()
                            : button.path("action").path("payload").path("a").asText()));

        }

        List<String> expected = new ArrayList<>();

        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
            for (InlineKeyboard.KeyboardButton button : row) {
                expected.add(button.actionId() != null ? button.actionId() : button.url());
            }
        }

        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected);

    }

    @Test
    void everyKeyboardOfEveryLanguageFitsTheVkInlineGeometry() throws IOException {

        Set<String> languages = shippedLanguages();
        MessageBundleHolder holder = new MessageBundleHolder("messages", null, languages, "en");
        MenuFactory menus = new MenuFactory(holder, GITHUB, List.copyOf(languages), Map.of(),
                ZoneCityNames.load(languages));

        // The gate must not go vacuous: a picker page really is taller than VK accepts,
        // so the mapper has to fold it on every run of this test.
        assertThat(menus.languages(Locale.ENGLISH, 0).rows().size())
                .as("language picker overflows the VK inline limit").isGreaterThan(6);
        assertThat(menus.zones(Locale.ENGLISH, 0, ZoneId.of("Europe/Moscow")).rows().size())
                .as("zone picker overflows the VK inline limit").isGreaterThan(6);

        for (String language : languages) {

            Locale locale = Locale.forLanguageTag(language);

            for (boolean notifyEnabled : new boolean[] {true, false}) {
                assertFitsVk(language + " main menu", menus.mainMenu(locale, notifyEnabled, null));
                assertFitsVk(language + " main menu with a web app",
                        menus.mainMenu(locale, notifyEnabled, "https://example.com/app"));
            }

            assertFitsVk(language + " settings", menus.settings(locale));
            assertFitsVk(language + " about", menus.about(locale));
            assertFitsVk(language + " confirm delete", menus.confirmDelete(locale));
            assertFitsVk(language + " days since toggle", menus.daysSinceToggle(locale, true));
            assertFitsVk(language + " reminder menu button", menus.menuButton(locale));
            assertFitsVk(language + " back", menus.back(locale));

            for (int page = 0; page < 4; page++) {
                assertFitsVk(language + " languages page " + page, menus.languages(locale, page));
            }

            for (int page = 0; page < 6; page++) {
                assertFitsVk(language + " zones page " + page,
                        menus.zones(locale, page, ZoneId.of("Europe/Moscow")));
            }

        }

    }

}
