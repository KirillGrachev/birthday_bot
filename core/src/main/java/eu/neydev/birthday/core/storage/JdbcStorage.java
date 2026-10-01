package eu.neydev.birthday.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.DeliverySettings;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.domain.ReminderKind;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * JDBC storage (SQLite / PostgreSQL) with a HikariCP pool and VERSIONED migrations.
 *
 * <p>The schema evolves through the {@link Migration} registry: the table
 * {@code schema_version} stores the applied version, migrations are applied
 * in order, each in its own transaction. {@code CREATE TABLE IF NOT EXISTS}
 * versionless ones are no longer used - a schema change is now predictable.
 *
 * <p>SQLite is tuned for load: WAL, {@code synchronous=NORMAL},
 * {@code busy_timeout}. Time is epoch-millis (BIGINT) on both DBMSs.
 * Upsert - {@code ON CONFLICT ... DO UPDATE} (one code path without dialect branches).
 */
public final class JdbcStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcStorage.class);

    private final HikariDataSource dataSource;
    private final ProfileRepository profiles;
    private final DeliveryLogRepository deliveryLog;
    private int schemaVersion;

    private JdbcStorage(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.profiles = new JdbcProfileRepository(dataSource);
        this.deliveryLog = new JdbcDeliveryLogRepository(dataSource);
    }

    public static JdbcStorage open(AppConfig.Storage config) {

        HikariDataSource dataSource = createDataSource(config);
        JdbcStorage storage = new JdbcStorage(dataSource);
        storage.migrate();

        return storage;

    }

    /** Migration registry: v1 - initial schema, then - evolution. */
    public static List<Migration> migrations() {

        return List.of(

                new Migration(1, "initial schema", List.of(
                        """
                        CREATE TABLE IF NOT EXISTS profiles (
                            platform         TEXT NOT NULL,
                            external_id      TEXT NOT NULL,
                            chat_id          TEXT NOT NULL,
                            locale           TEXT NOT NULL,
                            zone             TEXT NOT NULL,
                            birth_month      INTEGER,
                            birth_day        INTEGER,
                            birth_year       INTEGER,
                            notify_enabled   BOOLEAN NOT NULL,
                            notify_time      TEXT NOT NULL,
                            next_reminder_at BIGINT,
                            created_at       BIGINT NOT NULL,
                            updated_at       BIGINT NOT NULL,
                            PRIMARY KEY (platform, external_id)
                        )""",
                        "CREATE INDEX IF NOT EXISTS idx_profiles_due ON profiles (next_reminder_at)",
                        """
                        CREATE TABLE IF NOT EXISTS delivery_log (
                            platform    TEXT NOT NULL,
                            external_id TEXT NOT NULL,
                            kind        TEXT NOT NULL,
                            local_date  TEXT NOT NULL,
                            sent_at     BIGINT NOT NULL,
                            PRIMARY KEY (platform, external_id, kind, local_date)
                        )""")),

                new Migration(2, "delivery attempts counter (per-profile, persistent)",
                        List.of("ALTER TABLE profiles ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0"))

        );

    }

    private static HikariDataSource createDataSource(AppConfig.Storage config) {

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("birthday-db");
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setConnectionTimeout(5_000);

        if (config.type() == AppConfig.Storage.Type.SQLITE) {

            Path path = Path.of(config.sqlitePath()).toAbsolutePath();

            try {
                Files.createDirectories(path.getParent());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot create database directory: " + path.getParent(), e);
            }

            hikari.setJdbcUrl("jdbc:sqlite:" + path
                    + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&foreign_keys=ON");
            hikari.setMaximumPoolSize(Math.min(config.poolSize(), 8));
            hikari.setDriverClassName("org.sqlite.JDBC");

        } else {

            if (config.jdbcUrl() == null) {
                throw new IllegalStateException("storage.jdbc-url is required for storage.type=postgres");
            }

            hikari.setJdbcUrl(config.jdbcUrl());
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setDriverClassName("org.postgresql.Driver");

        }

        return new HikariDataSource(hikari);

    }

    private void migrate() {

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version    INTEGER PRIMARY KEY,
                            applied_at BIGINT NOT NULL
                        )""");
            }

            int current = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    current = rs.getInt(1);
                }
            }

            for (Migration migration : migrations()) {

                if (migration.version() <= current) {
                    continue;
                }

                try (Statement statement = connection.createStatement()) {

                    for (String sql : migration.sql()) {
                        statement.execute(sql);
                    }

                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
                        insert.setInt(1, migration.version());
                        insert.setLong(2, Instant.now().toEpochMilli());
                        insert.executeUpdate();
                    }

                }

                log.info("Schema migration v{} applied: {}", migration.version(), migration.description());

            }

            connection.commit();
            schemaVersion = currentVersion();

        } catch (SQLException e) {
            throw new IllegalStateException("Schema migration failed", e);
        }

    }

    private int currentVersion() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new StorageException("currentVersion()", e);
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public ProfileRepository profiles() {
        return profiles;
    }

    public DeliveryLogRepository deliveryLog() {
        return deliveryLog;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static final class JdbcProfileRepository implements ProfileRepository {

        private static final String SELECT_COLUMNS =
                "platform, external_id, chat_id, locale, zone, birth_month, birth_day, birth_year,"
                        + " notify_enabled, notify_time, next_reminder_at, attempts,"
                        + " created_at, updated_at";

        private final DataSource dataSource;

        private JdbcProfileRepository(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Optional<Profile> find(@NotNull PlatformUser user) {

            String sql = "SELECT " + SELECT_COLUMNS + " FROM profiles WHERE platform = ? AND external_id = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw new StorageException("find(" + user.key() + ")", e);
            }

        }

        @Override
        public void save(@NotNull Profile profile) {

            String sql = """
                    INSERT INTO profiles (platform, external_id, chat_id, locale, zone,
                                          birth_month, birth_day, birth_year,
                                          notify_enabled, notify_time, next_reminder_at, attempts,
                                          created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (platform, external_id) DO UPDATE SET
                        chat_id          = EXCLUDED.chat_id,
                        locale           = EXCLUDED.locale,
                        zone             = EXCLUDED.zone,
                        birth_month      = EXCLUDED.birth_month,
                        birth_day        = EXCLUDED.birth_day,
                        birth_year       = EXCLUDED.birth_year,
                        notify_enabled   = EXCLUDED.notify_enabled,
                        notify_time      = EXCLUDED.notify_time,
                        next_reminder_at = EXCLUDED.next_reminder_at,
                        attempts         = EXCLUDED.attempts,
                        updated_at       = EXCLUDED.updated_at
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                PlatformUser user = profile.user();
                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                statement.setString(3, profile.chatId());
                statement.setString(4, profile.locale().getLanguage());
                statement.setString(5, profile.zone().getId());
                setNullableInt(statement, 6, profile.birthDate() == null ? null : profile.birthDate().month());
                setNullableInt(statement, 7, profile.birthDate() == null ? null : profile.birthDate().day());
                setNullableInt(statement, 8, profile.birthDate() == null ? null : profile.birthDate().year());
                statement.setBoolean(9, profile.notifyEnabled());
                statement.setString(10, profile.notifyTime().toString());
                setNullableLong(statement, 11, profile.nextReminderAt() == null
                        ? null : profile.nextReminderAt().toEpochMilli());
                statement.setInt(12, profile.attempts());
                statement.setLong(13, profile.createdAt().toEpochMilli());
                statement.setLong(14, profile.updatedAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new StorageException("save(" + profile.user().key() + ")", e);
            }

        }

        @Override
        public List<Profile> findDue(@NotNull Instant now, int limit) {

            String sql = "SELECT " + SELECT_COLUMNS + " FROM profiles"
                    + " WHERE notify_enabled = ? AND next_reminder_at IS NOT NULL AND next_reminder_at <= ?"
                    + " ORDER BY next_reminder_at LIMIT ?";

            List<Profile> result = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, true);
                statement.setLong(2, now.toEpochMilli());
                statement.setInt(3, limit);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }

            } catch (SQLException e) {
                throw new StorageException("findDue(" + now + ")", e);
            }

            return result;

        }

        @Override
        public long count() {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM profiles")) {
                return rs.next() ? rs.getLong(1) : 0;
            } catch (SQLException e) {
                throw new StorageException("count()", e);
            }
        }

        @Override
        public void delete(@NotNull PlatformUser user) {

            String sql = "DELETE FROM profiles WHERE platform = ? AND external_id = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new StorageException("delete(" + user.key() + ")", e);
            }

        }

        private static Profile mapRow(ResultSet rs) throws SQLException {

            BirthDate birthDate = rs.getObject("birth_month") == null
                    ? null
                    : new BirthDate(rs.getInt("birth_month"), rs.getInt("birth_day"),
                    rs.getObject("birth_year") == null ? null : rs.getInt("birth_year"));

            DeliverySettings settings = new DeliverySettings(
                    ZoneId.of(rs.getString("zone")),
                    rs.getBoolean("notify_enabled"),
                    LocalTime.parse(rs.getString("notify_time")),
                    rs.getObject("next_reminder_at") == null
                            ? null : Instant.ofEpochMilli(rs.getLong("next_reminder_at")),
                    rs.getInt("attempts"));

            return new Profile(
                    new PlatformUser(Platform.fromId(rs.getString("platform")),
                            rs.getString("external_id")),
                    rs.getString("chat_id"),
                    Locale.forLanguageTag(rs.getString("locale")),
                    settings,
                    birthDate,
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")));

        }

        private static void setNullableInt(PreparedStatement statement, int index, Integer value)
                throws SQLException {
            if (value == null) {
                statement.setNull(index, java.sql.Types.INTEGER);
            } else {
                statement.setInt(index, value);
            }
        }

        private static void setNullableLong(PreparedStatement statement, int index, Long value)
                throws SQLException {
            if (value == null) {
                statement.setNull(index, java.sql.Types.BIGINT);
            } else {
                statement.setLong(index, value);
            }
        }

    }

    private static final class JdbcDeliveryLogRepository implements DeliveryLogRepository {

        private final DataSource dataSource;

        private JdbcDeliveryLogRepository(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean markSent(@NotNull PlatformUser user, @NotNull ReminderKind kind,
                                @NotNull LocalDate localDate, @NotNull Instant sentAt) {

            String sql = "INSERT INTO delivery_log (platform, external_id, kind, local_date, sent_at)"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (platform, external_id, kind, local_date) DO NOTHING";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                statement.setString(3, kind.name());
                statement.setString(4, localDate.toString());
                statement.setLong(5, sentAt.toEpochMilli());

                return statement.executeUpdate() > 0;

            } catch (SQLException e) {
                throw new StorageException("markSent(" + user.key() + ", " + kind + ")", e);
            }
        }

        @Override
        public void deleteFor(@NotNull PlatformUser user) {

            String sql = "DELETE FROM delivery_log WHERE platform = ? AND external_id = ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new StorageException("deleteFor(" + user.key() + ")", e);
            }

        }

        @Override
        public int pruneBefore(@NotNull LocalDate cutoff) {

            // local_date is stored as ISO-8601 text, so a lexicographic comparison is a
            // chronological one and the delete needs no date function of the dialect.
            String sql = "DELETE FROM delivery_log WHERE local_date < ?";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, cutoff.toString());
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new StorageException("pruneBefore(" + cutoff + ")", e);
            }

        }

        @Override
        public boolean wasSent(@NotNull PlatformUser user, @NotNull ReminderKind kind,
                               @NotNull LocalDate localDate) {

            String sql = "SELECT 1 FROM delivery_log WHERE platform = ? AND external_id = ?"
                    + " AND kind = ? AND local_date = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, user.platform().id());
                statement.setString(2, user.externalId());
                statement.setString(3, kind.name());
                statement.setString(4, localDate.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                throw new StorageException("wasSent(" + user.key() + ")", e);
            }

        }

    }

    /** Storage error: rethrown as unchecked - the pipeline decides whether to retry. */
    public static class StorageException extends RuntimeException {

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
