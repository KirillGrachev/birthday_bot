package eu.neydev.birthday.core.storage;

import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.domain.Profile;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Profile storage SPI. Implementations: SQLite and PostgreSQL (JDBC).
 * The contract is deliberately minimal and contains no platform types -
 * any other implementation can be plugged in if desired (Mongo, Redis, HTTP-API).
 */
public interface ProfileRepository {

    Optional<Profile> find(@NotNull PlatformUser user);

    /** Upsert by (platform, external_id). */
    void save(@NotNull Profile profile);

    /**
     * Profiles whose reminder is due, in a batch, ordered by due time ascending.
     * The basis of the scheduler tick: one indexed query instead of scanning all users.
     */
    List<Profile> findDue(@NotNull Instant now, int limit);

    long count();

    void delete(@NotNull PlatformUser user);

}
