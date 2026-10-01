package eu.neydev.birthday.core.conversation;

import eu.neydev.birthday.core.api.PlatformUser;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dialog states ("waiting for date", "waiting for time", "waiting for zone") with a TTL.
 * In-memory: state is ephemeral (on restart the user simply re-enters it),
 * but zero writes per button press - the profile in the DB is not touched needlessly.
 */
public final class ConversationStore {

    public enum State { AWAITING_BIRTH_DATE, AWAITING_NOTIFY_TIME, AWAITING_ZONE }

    private record Session(State state, Instant expiresAt) {
    }

    private final Map<PlatformUser, Session> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;

    public ConversationStore(Duration ttl) {
        this.ttl = ttl;
    }

    public void begin(@NotNull PlatformUser user, @NotNull State state) {
        sessions.put(user, new Session(state, Instant.now().plus(ttl)));
    }

    public Optional<State> current(@NotNull PlatformUser user) {

        Session session = sessions.get(user);

        if (session == null) {
            return Optional.empty();
        }

        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(user);

            return Optional.empty();
        }

        return Optional.of(session.state());

    }

    public void clear(@NotNull PlatformUser user) {
        sessions.remove(user);
    }

    /** Periodic cleanup of expired sessions (called by the scheduler). */
    public int purgeExpired() {

        Instant now = Instant.now();
        int before = sessions.size();

        sessions.entrySet().removeIf(entry ->
                entry.getValue().expiresAt().isBefore(now));

        return before - sessions.size();

    }

    public int size() {
        return sessions.size();
    }

}
