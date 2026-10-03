package eu.neydev.birthday.core.config;

import eu.neydev.birthday.core.api.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path write(String content) throws IOException {

        Path file = tempDir.resolve("application.yml");
        Files.writeString(file, content);

        return file;

    }

    @Test
    void loadsDefaultsAndEnvSubstitution() throws IOException {

        Path file = write("""
                storage:
                  type: sqlite
                  sqlite-path: data/test.db
                scheduler:
                  tick-interval: 15s
                  default-zone: Europe/Moscow
                pipeline: {}
                webapp: {}
                locale:
                  default: ru
                  supported: [ru, en]
                platforms:
                  telegram:
                    enabled: true
                    token: ${TG_TOKEN:secret-token}
                owners: [telegram:1]
                """);

        AppConfig config = new ConfigLoader(name -> null).load(file);

        assertThat(config.storage().type()).isEqualTo(AppConfig.Storage.Type.SQLITE);
        assertThat(config.scheduler().tickInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.platform(Platform.TELEGRAM).token()).isEqualTo("secret-token");
        assertThat(config.platform(Platform.TELEGRAM).isActive()).isTrue();
        assertThat(config.platform(Platform.DISCORD).isActive()).isFalse();
        assertThat(config.ownerKeys()).containsExactly("telegram:1");

    }

    @Test
    void telegramProxyParsedAndValidated() throws IOException {

        Path ok = write("""
                platforms:
                  telegram:
                    enabled: true
                    token: t
                    proxy: ${TG_PROXY:socks5://127.0.0.1:1080}
                """);

        AppConfig config = new ConfigLoader(name -> null).load(ok);
        assertThat(config.platform(Platform.TELEGRAM).proxy())
                .isEqualTo("socks5://127.0.0.1:1080");

        Path bad = write("""
                platforms:
                  telegram:
                    enabled: true
                    token: t
                    proxy: not-a-proxy
                """);

        assertThatThrownBy(() -> new ConfigLoader(name -> null).load(bad))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("proxy");

    }

    @Test
    void proxyWithLoginAccepted() throws IOException {

        String[] ok = {
                "socks5://203.0.113.7:1080:proxyUser:proxyPass",
                "203.0.113.7:1080:proxyUser:proxyPass",
                "http://user:pass@203.0.113.9:8080",
                "socks5://user:pass@203.0.113.9:1080",
                "socks5://[2001:db8::1]:1080:user:pass",
        };

        for (String proxy : ok) {
            Path file = write("""
                    platforms:
                      telegram:
                        enabled: true
                        token: t
                        proxy: '%s'
                    """.formatted(proxy));
            assertThat(new ConfigLoader(name -> null).load(file).platform(Platform.TELEGRAM).proxy())
                    .isEqualTo(proxy);
        }

    }

    @Test
    void proxyMalformedRejected() throws IOException {

        String[] bad = {
                "203.0.113.9:1080:1080",
                "203.0.113.9:notaport",
                "203.0.113.9:0",
                "203.0.113.9:70000",
                ":1080",
                "203.0.113.9:1080:a:b:c",
        };

        for (String proxy : bad) {
            Path file = write("""
                    platforms:
                      telegram:
                        enabled: true
                        token: t
                        proxy: '%s'
                    """.formatted(proxy));
            assertThatThrownBy(() -> new ConfigLoader(name -> null).load(file))
                    .as("proxy '%s'", proxy)
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("proxy");
        }

    }

    @Test
    void envOverridesDefault() throws IOException {

        Path file = write("""
                platforms:
                  telegram:
                    enabled: true
                    token: ${TG_TOKEN:fallback}
                """);

        AppConfig config = new ConfigLoader(Map.of("TG_TOKEN", "real")::get).load(file);
        assertThat(config.platform(Platform.TELEGRAM).token()).isEqualTo("real");

    }

    @Test
    void missingEnvWithoutDefaultFailsFast() throws IOException {

        Path file = write("""
                platforms:
                  telegram:
                    enabled: true
                    token: ${TG_TOKEN_MUST_EXIST}
                """);

        assertThatThrownBy(() -> new ConfigLoader(name -> null).load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("TG_TOKEN_MUST_EXIST");

    }

    @Test
    void invalidValuesReportKeyPath() throws IOException {

        Path file = write("""
                scheduler:
                  batch-size: -5
                """);

        assertThatThrownBy(() -> new ConfigLoader(name -> null).load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("batch-size");

    }

    @Test
    void unknownStorageTypeRejected() throws IOException {

        Path file = write("""
                storage:
                  type: mongodb
                """);

        assertThatThrownBy(() -> new ConfigLoader(name -> null).load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("storage.type");

    }

    @Test
    void localeDisplayNamesParsedInConfiguredOrder() throws IOException {

        Path file = write("""
                locale:
                  default: ru
                  supported: [ru, en, de]
                  display-names:
                    ru: Русский
                    en: English
                    de: Deutsch
                """);

        AppConfig.Locale locale = new ConfigLoader(name -> null).load(file).locale();

        assertThat(locale.supported()).containsExactly("ru", "en", "de");
        assertThat(locale.displayNames())
                .containsEntry("ru", "Русский")
                .containsEntry("en", "English")
                .containsEntry("de", "Deutsch");

    }

    @Test
    void localeDisplayNamesRejectUnknownLanguage() throws IOException {

        Path file = write("""
                locale:
                  default: ru
                  supported: [ru, en]
                  display-names:
                    ru: Русский
                    tr: Türkçe
                """);

        assertThatThrownBy(() -> new ConfigLoader(name -> null).load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("locale.display-names.tr");

    }

    @Test
    void ownersComeFromTheEnvironmentAsACommaSeparatedString() throws IOException {

        Path file = write("""
                storage:
                  type: sqlite
                  sqlite-path: data/test.db
                scheduler:
                  tick-interval: 15s
                  default-zone: Europe/Moscow
                pipeline: {}
                webapp: {}
                locale:
                  default: ru
                  supported: [ru, en]
                platforms:
                  telegram:
                    enabled: true
                    token: ${TG_TOKEN:secret-token}
                owners: ${OWNERS:}
                """);

        AppConfig config = new ConfigLoader(name ->
                "OWNERS".equals(name) ? "telegram:1, vk:2 , discord:3" : null).load(file);

        assertThat(config.ownerKeys()).containsExactly("telegram:1", "vk:2", "discord:3");

    }

    @Test
    void anEmptyOwnersEnvironmentMeansNobodyOwnsTheBot() throws IOException {

        Path file = write("""
                storage:
                  type: sqlite
                  sqlite-path: data/test.db
                scheduler:
                  tick-interval: 15s
                  default-zone: Europe/Moscow
                pipeline: {}
                webapp: {}
                locale:
                  default: ru
                  supported: [ru, en]
                platforms:
                  telegram:
                    enabled: true
                    token: ${TG_TOKEN:secret-token}
                owners: ${OWNERS:}
                """);

        AppConfig config = new ConfigLoader(name -> "").load(file);
        assertThat(config.ownerKeys()).isEmpty();

    }
}
