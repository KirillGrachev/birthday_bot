package eu.neydev.birthday.app;

import eu.neydev.birthday.core.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The style gate for everything a user can read: every shipped language must stay
 * free of the defects that reached production once and must never come back.
 *
 * <ul>
 *   <li>No em dash: it was used as a universal separator and read like a telegraph wire
 *       ("133 days left - February 11, 2027"); sentences now connect with words.</li>
 *   <li>No doubled dot: a Russian date in the CLDR long style ends with "г.", and a full
 *       stop after it printed "г.." to every user.</li>
 *   <li>No "open source" loanword in the Russian copy: there the phrase is spelled out in
 *       Russian, while each other language uses its own natural term.</li>
 *   <li>Placeholder parity: a key whose Russian text promises {time} must carry {time} in
 *       German too, or the German user sees an unrendered brace.</li>
 * </ul>
 */
class MessageTextQualityTest {

    /** Every shipped language: a new bundle joins the style gate the moment it lands. */
    private static final Set<String> LANGUAGES = shippedLanguages();

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

    /** Placeholder names with the optional plural clause: {@code {days|plural:one=...}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)(?:\\|[^}]*)?\\}");

    private final MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

    @Test
    void noEmDashReachesAUser() {

        List<String> violations = new ArrayList<>();

        for (String language : LANGUAGES) {
            for (String key : bundle.keys(language)) {
                String value = bundle.get(key, language).orElse("");

                if (value.contains("—") || value.contains("–")) {
                    violations.add(language + ": " + key);
                }
            }
        }

        assertThat(violations).as("em dashes in user-facing text").isEmpty();

    }

    @Test
    void noSentenceEndsInADoubledDot() {

        List<String> violations = new ArrayList<>();

        for (String language : LANGUAGES) {
            for (String key : bundle.keys(language)) {
                String value = bundle.get(key, language).orElse("");

                if (value.contains("..")) {
                    violations.add(language + ": " + key + " -> " + value);
                }
            }
        }

        assertThat(violations).as("doubled dots in user-facing text").isEmpty();

    }

    @Test
    void russianCopySpellsOpenSourceOutInRussian() {

        List<String> violations = new ArrayList<>();

        for (String key : bundle.keys("ru")) {
            String value = bundle.get(key, "ru").orElse("").toLowerCase(Locale.ROOT);

            if (value.contains("open source") || value.contains("open-source")
                    || value.contains("opensource")) {
                violations.add(key);
            }
        }

        assertThat(violations).as("latin loanword in the Russian copy").isEmpty();

    }

    @Test
    void everyLanguageCarriesTheSamePlaceholdersPerKey() {

        List<String> violations = new ArrayList<>();

        for (String key : new TreeSet<>(bundle.keys("ru"))) {
            Set<String> expected = placeholders(bundle.get(key, "ru").orElse(""));

            for (String language : LANGUAGES) {
                Set<String> actual = placeholders(bundle.get(key, language).orElse(""));

                if (!actual.equals(expected)) {
                    violations.add(key + ": ru=" + expected + " " + language + "=" + actual);
                }
            }
        }

        assertThat(violations).as("keys whose placeholders differ between languages").isEmpty();

    }

    private static Set<String> placeholders(String value) {

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);

        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        return names;

    }

}
