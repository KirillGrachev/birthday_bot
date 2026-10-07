package eu.neydev.birthday.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.KeyboardLimits;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.command.ZoneCatalog;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.i18n.ZoneCityNames;
import eu.neydev.birthday.core.text.RichText;
import eu.neydev.birthday.platform.discord.DiscordKeyboardMapper;
import eu.neydev.birthday.platform.slack.SlackRenderers;
import eu.neydev.birthday.platform.telegram.TelegramKeyboardMapper;
import eu.neydev.birthday.platform.viber.ViberKeyboardMapper;
import eu.neydev.birthday.platform.vk.VkKeyboardMapper;
import eu.neydev.birthday.platform.whatsapp.WhatsAppPayloadBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * Every keyboard the core builds, in every shipped language, seen through the mapper of
 * every platform we ship: inside that platform's own geometry, and with not one action
 * lost on the way. The geometry is read from {@link KeyboardLimits}, so the test also
 * fails when a mapper and the declared budget of its platform drift apart.
 *
 * <p>This is the net under a production failure. VK refused the language and zone pickers
 * whole with error 911 ("buttons contain too much rows"), because a picker page is seven
 * rows and Telegram - the platform the pickers were drawn on - renders seven rows without
 * a second thought. Folding repaired the rows and VK then refused the same pickers for its
 * other number, a count of ten buttons whole ("keyboard contains too much buttons"), which
 * no fold can shrink: the pickers page to it instead, and the sweep below checks the count
 * as well as the geometry. The same arithmetic was then wrong on four more platforms, each
 * in its own way: Discord stops at five action rows and JDA refuses the payload before a
 * request is even built, Slack wants a text object on every button and answers a bare
 * string with {@code invalid_blocks}, WhatsApp hides everything past three buttons and
 * used to send a menu with no controls on it at all, and Viber flows buttons into a
 * six-column grid where a row that leaves a column free shifts every row below it.
 *
 * <p>The pickers are built to the budget of the platform they are checked against, the way
 * the core builds them in production, so this test fails when a page grows past what a
 * platform can carry rather than only when a mapper stops folding.
 */
class PlatformKeyboardGeometryTest {

    private static final String GITHUB = "https://github.com/example/repo";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Telegram counts the payload of a callback button in bytes, not characters. */
    private static final int TELEGRAM_CALLBACK_BYTES = 64;

    /** Discord counts the custom id in bytes as well, and allows a hundred of them. */
    private static final int DISCORD_CUSTOM_ID_BYTES = 100;

    /** WhatsApp shows twenty characters on a reply button and on the list control. */
    private static final int WHATSAPP_CONTROL_TITLE = 20;

    /** A row of a WhatsApp list keeps seventy-two characters for a description. */
    private static final int WHATSAPP_ROW_DESCRIPTION = 72;

    /** A WhatsApp list may have ten sections; ours never needs a second one. */
    private static final int WHATSAPP_LIST_SECTIONS = 10;

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

    /** Every screen a reader can reach, with the pickers paged the way that platform pages. */
    private static List<Named> keyboards(MenuFactory menus, Locale locale, KeyboardLimits limits) {

        List<Named> keyboards = new ArrayList<>();

        for (boolean notifyEnabled : new boolean[] {true, false}) {
            keyboards.add(new Named("main menu", menus.mainMenu(locale, notifyEnabled, null)));
            keyboards.add(new Named("main menu with a web app",
                    menus.mainMenu(locale, notifyEnabled, "https://example.com/app")));
        }

        keyboards.add(new Named("settings", menus.settings(locale)));
        keyboards.add(new Named("about", menus.about(locale)));
        keyboards.add(new Named("confirm delete", menus.confirmDelete(locale)));
        keyboards.add(new Named("days since toggle", menus.daysSinceToggle(locale, true)));
        keyboards.add(new Named("days since toggle back", menus.daysSinceToggle(locale, false)));
        keyboards.add(new Named("reminder menu button", menus.menuButton(locale)));
        keyboards.add(new Named("back", menus.back(locale)));

        int languagePages = pages(menus.availableLanguages().size(),
                limits.pickerItems(MenuFactory.LANGUAGE_PAGE_CHROME));

        for (int page = 0; page < languagePages; page++) {
            keyboards.add(new Named("languages page " + page,
                    menus.languages(locale, page, limits)));
        }

        int zonePages = pages(ZoneCatalog.POPULAR.size(),
                limits.pickerItems(MenuFactory.ZONE_PAGE_CHROME));

        for (int page = 0; page < zonePages; page++) {
            keyboards.add(new Named("zones page " + page,
                    menus.zones(locale, page, ZoneId.of("Europe/Moscow"), limits)));
        }

        return keyboards;

    }

    private static int pages(int items, int perPage) {
        return Math.max(1, (items + perPage - 1) / perPage);
    }

    private record Named(String what, InlineKeyboard keyboard) {
    }

    private static List<String> expected(InlineKeyboard keyboard) {

        List<String> actions = new ArrayList<>();

        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
            for (InlineKeyboard.KeyboardButton button : row) {
                actions.add(button.actionId() != null ? button.actionId() : button.url());
            }
        }

        return actions;

    }

    @Test
    void everyKeyboardOfEveryLanguageFitsEveryPlatform() throws IOException {

        Set<String> languages = shippedLanguages();
        MessageBundleHolder holder = new MessageBundleHolder("messages", null, languages, "en");
        MenuFactory menus = new MenuFactory(holder, GITHUB, List.copyOf(languages), Map.of(),
                ZoneCityNames.load(languages));

        // The gate must not go vacuous: a picker drawn for Telegram really is taller than
        // VK accepts and really carries more buttons than VK counts, so there is something
        // to fold and something to page down on every run of this test.
        KeyboardLimits vk = KeyboardLimits.of(Platform.VK);
        InlineKeyboard widestLanguages = menus.languages(Locale.ENGLISH, 0);
        InlineKeyboard widestZones = menus.zones(Locale.ENGLISH, 0, ZoneId.of("Europe/Moscow"));

        assertThat(widestLanguages.rows().size())
                .as("a language picker page overflows the VK row limit")
                .isGreaterThan(vk.maxRows());
        assertThat(widestZones.rows().size())
                .as("a zone picker page overflows the VK row limit")
                .isGreaterThan(vk.maxRows());
        assertThat(widestLanguages.callbacks())
                .as("a language picker page overflows the VK button count")
                .hasSizeGreaterThan(vk.maxButtons());
        assertThat(widestZones.callbacks())
                .as("a zone picker page overflows the VK button count")
                .hasSizeGreaterThan(vk.maxButtons());

        for (Platform platform : Platform.values()) {

            KeyboardLimits limits = KeyboardLimits.of(platform);

            for (String language : languages) {

                Locale locale = Locale.forLanguageTag(language);

                for (Named named : keyboards(menus, locale, limits)) {

                    String what = platform.id() + " / " + language + " / " + named.what();

                    switch (platform) {
                        case TELEGRAM -> assertTelegram(what, named.keyboard(), limits);
                        case VK -> assertVk(what, named.keyboard(), limits);
                        case DISCORD -> assertDiscord(what, named.keyboard(), limits);
                        case SLACK -> assertSlack(what, named.keyboard(), limits);
                        case WHATSAPP -> assertWhatsApp(what, named.keyboard(), limits);
                        case VIBER -> assertViber(what, named.keyboard(), limits);
                    }

                }

            }

        }

    }

    private static void assertTelegram(String what, InlineKeyboard keyboard, KeyboardLimits limits) {

        InlineKeyboardMarkup markup = TelegramKeyboardMapper.map(keyboard);
        assertThat(markup).as(what).isNotNull();

        List<String> mapped = new ArrayList<>();
        int buttons = 0;

        for (var row : markup.getKeyboard()) {

            assertThat(row).as(what + ": buttons in a row")
                    .hasSizeLessThanOrEqualTo(limits.maxButtonsInRow());

            for (InlineKeyboardButton button : row) {

                buttons++;

                // Telegram refuses rather than clips, so a caption past its limit is a
                // message that never arrives.
                assertThat(button.getText()).as(what + ": caption")
                        .hasSizeBetween(1, limits.labelChars());

                if (button.getUrl() != null) {
                    mapped.add(button.getUrl());
                } else {
                    assertThat(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length)
                            .as(what + ": callback data").isLessThanOrEqualTo(TELEGRAM_CALLBACK_BYTES);
                    mapped.add(button.getCallbackData());
                }

            }

        }

        assertThat(buttons).as(what + ": buttons in a keyboard")
                .isLessThanOrEqualTo(limits.maxButtons());
        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected(keyboard));

    }

    private static void assertVk(String what, InlineKeyboard keyboard, KeyboardLimits limits)
            throws IOException {

        JsonNode rows = JSON.readTree(VkKeyboardMapper.map(keyboard)).path("buttons");
        assertThat(rows.size()).as(what + ": inline rows").isLessThanOrEqualTo(limits.maxRows());

        List<String> mapped = new ArrayList<>();

        for (JsonNode row : rows) {

            assertThat(row.size()).as(what + ": buttons in a row")
                    .isLessThanOrEqualTo(limits.maxButtonsInRow());

            for (JsonNode button : row) {

                assertThat(button.path("action").path("label").asText())
                        .as(what + ": caption").hasSizeBetween(1, limits.labelChars());

                mapped.add(button.path("action").path("type").asText().equals("open_link")
                        ? button.path("action").path("link").asText()
                        : button.path("action").path("payload").path("a").asText());

            }

        }

        assertThat(mapped).as(what + ": buttons in a keyboard")
                .hasSizeLessThanOrEqualTo(limits.maxButtons());
        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected(keyboard));

    }

    private static void assertDiscord(String what, InlineKeyboard keyboard, KeyboardLimits limits) {

        List<ActionRow> rows = DiscordKeyboardMapper.components(keyboard);
        assertThat(rows).as(what + ": action rows").hasSizeLessThanOrEqualTo(limits.maxRows());

        List<String> mapped = new ArrayList<>();

        for (ActionRow row : rows) {

            assertThat(row.getComponents()).as(what + ": buttons in a row")
                    .hasSizeLessThanOrEqualTo(limits.maxButtonsInRow());

            for (var component : row.getComponents()) {

                Button button = (Button) component;

                assertThat(button.getLabel()).as(what + ": caption")
                        .hasSizeBetween(1, limits.labelChars());

                if (button.getUrl() != null) {
                    mapped.add(button.getUrl());
                } else {
                    assertThat(button.getCustomId().getBytes(StandardCharsets.UTF_8).length)
                            .as(what + ": custom id").isLessThanOrEqualTo(DISCORD_CUSTOM_ID_BYTES);
                    mapped.add(button.getCustomId());
                }

            }

        }

        assertThat(mapped).as(what + ": buttons in a keyboard")
                .hasSizeLessThanOrEqualTo(limits.maxButtons());
        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected(keyboard));

    }

    private static void assertSlack(String what, InlineKeyboard keyboard, KeyboardLimits limits) {

        JsonNode blocks = SlackRenderers.blocks(keyboard);
        assertThat(blocks.size()).as(what + ": blocks").isLessThanOrEqualTo(limits.maxRows());

        List<String> mapped = new ArrayList<>();

        for (JsonNode block : blocks) {

            assertThat(block.path("type").asText()).as(what + ": block type").isEqualTo("actions");
            assertThat(block.path("elements").size()).as(what + ": elements in a block")
                    .isLessThanOrEqualTo(limits.maxButtonsInRow());

            for (JsonNode element : block.path("elements")) {

                assertThat(element.path("type").asText()).as(what + ": element").isEqualTo("button");
                assertThat(element.path("text").path("type").asText())
                        .as(what + ": caption is a text object").isEqualTo("plain_text");
                assertThat(element.path("text").path("text").asText())
                        .as(what + ": caption").hasSizeBetween(1, limits.labelChars());

                if (element.has("style")) {
                    assertThat(element.path("style").asText()).as(what + ": style")
                            .isIn("primary", "danger");
                }

                mapped.add(element.has("url")
                        ? element.path("url").asText()
                        : element.path("action_id").asText());

            }

        }

        assertThat(mapped).as(what + ": buttons in a keyboard")
                .hasSizeLessThanOrEqualTo(limits.maxButtons());
        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected(keyboard));

    }

    /**
     * WhatsApp carries no rows at all: a keyboard is either three reply buttons or a list
     * of ten rows, so the check is on the flattened controls. Links have no button here and
     * arrive as lines of text, which is the one place an action leaves the keyboard.
     */
    private static void assertWhatsApp(String what, InlineKeyboard keyboard, KeyboardLimits limits) {

        ObjectNode payload = WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("body"), keyboard);
        JsonNode interactive = payload.path("interactive");
        List<InlineKeyboard.KeyboardButton> callbacks = keyboard.callbacks();

        if (callbacks.isEmpty()) {
            assertThat(payload.path("type").asText()).as(what).isEqualTo("text");
            return;
        }

        assertThat(interactive.path("body").path("text").asText()).as(what + ": body").isNotEmpty();

        List<String> mapped = new ArrayList<>();

        if (callbacks.size() <= 3) {

            assertThat(interactive.path("type").asText()).as(what).isEqualTo("button");

            JsonNode buttons = interactive.path("action").path("buttons");
            assertThat(buttons.size()).as(what + ": reply buttons").isLessThanOrEqualTo(3);

            for (JsonNode button : buttons) {

                assertThat(button.path("reply").path("title").asText())
                        .as(what + ": button title").hasSizeBetween(1, WHATSAPP_CONTROL_TITLE);
                assertThat(button.path("reply").path("id").asText()).as(what + ": button id")
                        .isNotEmpty();
                mapped.add(button.path("reply").path("id").asText());

            }

        } else {

            assertThat(interactive.path("type").asText()).as(what).isEqualTo("list");
            assertThat(interactive.path("action").path("button").asText())
                    .as(what + ": the control that opens the list")
                    .hasSizeBetween(1, WHATSAPP_CONTROL_TITLE);

            JsonNode sections = interactive.path("action").path("sections");
            assertThat(sections.size()).as(what + ": sections")
                    .isPositive().isLessThanOrEqualTo(WHATSAPP_LIST_SECTIONS);

            int rows = 0;

            for (JsonNode section : sections) {

                // A section needs a title only when there is more than one of them.
                if (sections.size() > 1) {
                    assertThat(section.path("title").asText()).as(what + ": section title")
                            .isNotEmpty();
                }

                for (JsonNode row : section.path("rows")) {

                    rows++;
                    assertThat(row.path("title").asText()).as(what + ": row title")
                            .hasSizeBetween(1, limits.labelChars());
                    assertThat(row.path("description").asText()).as(what + ": row description")
                            .hasSizeLessThanOrEqualTo(WHATSAPP_ROW_DESCRIPTION);
                    assertThat(row.path("id").asText()).as(what + ": row id").isNotEmpty();
                    mapped.add(row.path("id").asText());

                }

            }

            assertThat(rows).as(what + ": list rows").isLessThanOrEqualTo(limits.maxButtons());

        }

        assertThat(mapped).as(what + ": every choice survives")
                .containsExactlyInAnyOrderElementsOf(callbacks.stream()
                        .map(InlineKeyboard.KeyboardButton::actionId).toList());

        String body = interactive.path("body").path("text").asText("");

        for (InlineKeyboard.KeyboardButton link : keyboard.links()) {
            assertThat(body).as(what + ": a link survives as text").contains(link.url());
        }

    }

    private static void assertViber(String what, InlineKeyboard keyboard, KeyboardLimits limits) {

        JsonNode buttons = java.util.Objects
                .requireNonNull(ViberKeyboardMapper.map(keyboard), what + ": a keyboard")
                .path("Buttons");

        List<String> mapped = new ArrayList<>();
        int columns = 0;
        int rows = 0;
        int used = 0;

        for (JsonNode button : buttons) {

            int width = button.path("Columns").asInt();

            assertThat(width).as(what + ": columns").isBetween(1, limits.maxButtonsInRow());
            assertThat(button.path("Rows").asInt()).as(what + ": height").isBetween(1, 2);
            assertThat(button.path("Text").asText()).as(what + ": caption")
                    .hasSizeBetween(1, limits.labelChars());

            if (used + width > limits.maxButtonsInRow()) {
                rows++;
                used = 0;
            }

            used += width;
            columns += width;

            mapped.add(button.path("ActionType").asText().equals("open-url")
                    ? button.path("ActionBody").asText()
                    : button.path("TrackingData").asText());

        }

        if (used > 0) {
            rows++;
        }

        // Every core row fills the grid exactly: a row that leaves a column free pulls the
        // next button up into the gap and shifts the whole keyboard by one.
        assertThat(columns % limits.maxButtonsInRow())
                .as(what + ": each row fills the grid").isZero();
        assertThat(rows).as(what + ": grid rows").isLessThanOrEqualTo(limits.maxRows());
        assertThat(mapped).as(what + ": buttons in a keyboard")
                .hasSizeLessThanOrEqualTo(limits.maxButtons());
        assertThat(mapped).as(what + ": every action survives")
                .containsExactlyInAnyOrderElementsOf(expected(keyboard));

    }

}
