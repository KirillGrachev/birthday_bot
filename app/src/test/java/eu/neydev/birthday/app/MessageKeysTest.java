package eu.neydev.birthday.app;

import eu.neydev.birthday.core.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The i18n contract: every message key the code asks for exists in EVERY shipped
 * language, and all languages carry exactly the same key set.
 *
 * <p>This is the regression net for two real incidents: a block that drifted into the
 * wrong YAML section (the bot answered {@code [missing:message.about]}) and languages
 * silently diverging after a translation edit. Keys are collected by scanning the main
 * sources for string literals in the dotted namespaces, so a new key that is used but
 * not translated fails the build instead of reaching a user.
 */
class MessageKeysTest {

    /** Every language that ships a bundle: the gate widens on its own when a file is added. */
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

    /** Dotted message keys as they appear in the code: {@code "message.settings.view"}. */
    private static final Pattern KEY = Pattern.compile(
            "\"((?:message|button|command|reminder|webapp)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*)\"");

    @Test
    void everyKeyUsedInCodeExistsInEveryLanguage() throws IOException {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);
        Set<String> used = usedKeys();

        List<String> missing = new ArrayList<>();

        for (String key : used) {
            for (String language : LANGUAGES) {
                if (bundle.get(key, language).isEmpty()) {
                    missing.add(language + ": " + key);
                }
            }
        }

        assertThat(missing)
                .as("keys used in the code but absent from a language file")
                .isEmpty();

    }

    @Test
    void allLanguagesCarryTheSameKeySet() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);
        Set<String> reference = keySetOf(bundle, "en");

        for (String language : LANGUAGES) {

            if (language.equals("en")) {
                continue;
            }

            Set<String> other = keySetOf(bundle, language);
            Set<String> onlyEn = new TreeSet<>(reference);
            onlyEn.removeAll(other);
            Set<String> onlyOther = new TreeSet<>(other);
            onlyOther.removeAll(reference);

            assertThat(onlyEn).as("keys missing in " + language).isEmpty();
            assertThat(onlyOther).as("keys extra in " + language).isEmpty();

        }

    }

    /** Multi-line templates must stay multi-line: a YAML list is joined with newlines. */
    @Test
    void multilineTemplatesKeepTheirLineBreaks() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

        for (String language : LANGUAGES) {

            String start = bundle.get("message.start", language).orElseThrow();
            assertThat(start)
                    .as("message.start in " + language)
                    .contains("\n")
                    .doesNotStartWith("[")
                    .doesNotContain(", ,");

        }

    }

    private static Set<String> keySetOf(MessageBundle bundle, String language) {
        return new TreeSet<>(bundle.keys(language));
    }

    private static Set<String> usedKeys() throws IOException {
        return usedKeysQuietly();
    }

    private static Set<String> usedKeysQuietly() {

        Set<String> keys = new TreeSet<>();

        try (Stream<Path> files = Files.walk(repositoryRoot())) {

            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith(".java")).toList()) {

                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) keys.add(matcher.group(1));

            }

        } catch (IOException e) {
            throw new IllegalStateException("cannot scan sources", e);
        }

        return keys;

    }

    private static Path repositoryRoot() {

        Path dir = Path.of("").toAbsolutePath();

        while (dir != null) {

            if (Files.isDirectory(dir.resolve("core")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }

            dir = dir.getParent();

        }

        throw new IllegalStateException("repository root not found");

    }

}
