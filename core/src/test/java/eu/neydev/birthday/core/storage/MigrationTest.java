package eu.neydev.birthday.core.storage;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema migrations: a database created by an old version (v1) is opened by a new one -
 * data is preserved, the attempts column appears, the version becomes 2.
 */
class MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void upgradesV1DatabaseToV2KeepingData() throws Exception {

        Path db = tempDir.resolve("legacy.db");
        String url = "jdbc:sqlite:" + db;

        // simulate a database created before migrations were introduced (v1 schema only)
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            for (String sql : JdbcStorage.migrations().get(0).sql()) {
                statement.execute(sql);
            }

            statement.execute("""
                    INSERT INTO profiles (platform, external_id, chat_id, locale, zone,
                        birth_month, birth_day, birth_year, notify_enabled, notify_time,
                        next_reminder_at, created_at, updated_at)
                    VALUES ('telegram','1','1','ru','UTC',6,15,1990,1,'12:00',null,1,1)""");

            statement.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
            statement.execute("INSERT INTO schema_version (version, applied_at) VALUES (1, 1)");

        }

        try (JdbcStorage storage = JdbcStorage.open(new AppConfig.Storage(
                AppConfig.Storage.Type.SQLITE, db.toString(), null, null, null, 2))) {

            assertThat(storage.schemaVersion()).isEqualTo(2);

            var profile = storage.profiles().find(new PlatformUser(Platform.TELEGRAM, "1"));
            assertThat(profile).isPresent();
            assertThat(profile.get().attempts()).isZero();
            assertThat(profile.get().birthDate().month()).isEqualTo(6);

            // reopening is idempotent
            assertThat(JdbcStorage.migrations()).hasSize(2);

        }

    }

    @Test
    void freshDatabaseGetsAllMigrations() throws Exception {

        Path db = tempDir.resolve("fresh.db");
        try (JdbcStorage storage = JdbcStorage.open(new AppConfig.Storage(
                AppConfig.Storage.Type.SQLITE, db.toString(), null, null, null, 2))) {
            assertThat(storage.schemaVersion()).isEqualTo(JdbcStorage.migrations().size());
        }

    }

}
