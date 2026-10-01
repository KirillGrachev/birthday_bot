package eu.neydev.birthday.core;

import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.domain.ReminderKind;
import eu.neydev.birthday.core.storage.DeliveryLogRepository;
import eu.neydev.birthday.core.storage.ProfileRepository;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory storage for service and scheduler tests. */
public final class InMemoryStorage {

    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private final Set<String> deliveryLog = ConcurrentHashMap.newKeySet();

    public ProfileRepository profiles() {
        return new ProfileRepository() {

            @Override
            public Optional<Profile> find(@NotNull PlatformUser user) {
                return Optional.ofNullable(profiles.get(user.key()));
            }

            @Override
            public void save(@NotNull Profile profile) {
                profiles.put(profile.user().key(), profile);
            }

            @Override
            public java.util.List<Profile> findDue(@NotNull Instant now, int limit) {
                return profiles.values().stream()
                        .filter(Profile::notifyEnabled)
                        .filter(profile -> profile.nextReminderAt() != null
                                && !profile.nextReminderAt().isAfter(now))
                        .sorted(Comparator.comparing(Profile::nextReminderAt))
                        .limit(limit)
                        .toList();
            }

            @Override
            public long count() {
                return profiles.size();
            }

            @Override
            public void delete(@NotNull PlatformUser user) {
                profiles.remove(user.key());
            }

        };
    }

    public DeliveryLogRepository deliveryLog() {
        return new DeliveryLogRepository() {

            @Override
            public boolean markSent(@NotNull PlatformUser user, @NotNull ReminderKind kind,
                                    java.time.@NotNull LocalDate localDate, @NotNull Instant sentAt) {
                return deliveryLog.add(key(user, kind, localDate));
            }

            @Override
            public boolean wasSent(@NotNull PlatformUser user, @NotNull ReminderKind kind, java.time.@NotNull LocalDate localDate) {
                return deliveryLog.contains(key(user, kind, localDate));
            }

            @Override
            public void deleteFor(@NotNull PlatformUser user) {
                deliveryLog.removeIf(entry -> entry.startsWith(user.key() + "|"));
            }

            @Override
            public int pruneBefore(java.time.@NotNull LocalDate cutoff) {

                int before = deliveryLog.size();
                deliveryLog.removeIf(entry -> {

                    int at = entry.lastIndexOf('|');
                    return at > 0
                            && java.time.LocalDate.parse(entry.substring(at + 1)).isBefore(cutoff);

                });
                return before - deliveryLog.size();

            }

            private String key(PlatformUser user, ReminderKind kind, java.time.LocalDate date) {
                return user.key() + "|" + kind + "|" + date;
            }

        };
    }

}
