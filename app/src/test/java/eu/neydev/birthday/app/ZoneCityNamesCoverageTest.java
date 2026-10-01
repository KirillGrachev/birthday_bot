package eu.neydev.birthday.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zone picker speaks every language the messages do: a bundle without a
 * city catalog would silently drop its readers back to Latin zone ids, which
 * is exactly the defect the catalog was created to close.
 */
class ZoneCityNamesCoverageTest {

    private static Set<String> languagesOf(String directory) throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", directory))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void everyMessageLanguageCarriesACityCatalog() throws IOException {
        assertThat(languagesOf("zone-cities")).containsAll(languagesOf("messages"));
    }

    @Test
    void catalogsCarryNoLanguageTheBotDoesNotSpeak() throws IOException {
        assertThat(languagesOf("messages")).containsAll(languagesOf("zone-cities"));
    }
}
