package eu.neydev.birthday.core.service;

import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.LocaleResolver;
import eu.neydev.birthday.core.schedule.NextReminderCalculator;
import eu.neydev.birthday.core.storage.DeliveryLogRepository;
import eu.neydev.birthday.core.storage.ProfileRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Profile operations: creation from a platform hint, mutations with
 * recomputing the reminder cursor. The single point where the profile is written -
 * no scattered updates across the code.
 */
public final class ProfileService {

    private final ProfileRepository repository;
    private final DeliveryLogRepository deliveryLog;
    private final LocaleResolver localeResolver;
    private final NextReminderCalculator calculator;
    private final AppConfig.Scheduler schedulerConfig;
    private final Clock clock;

    public ProfileService(ProfileRepository repository,
                          DeliveryLogRepository deliveryLog,
                          LocaleResolver localeResolver,
                          NextReminderCalculator calculator,
                          AppConfig.Scheduler schedulerConfig,
                          Clock clock) {
        this.repository = repository;
        this.deliveryLog = deliveryLog;
        this.localeResolver = localeResolver;
        this.calculator = calculator;
        this.schedulerConfig = schedulerConfig;
        this.clock = clock;
    }

    public Profile getOrCreate(@NotNull PlatformUser user, @NotNull String chatId,
                               @Nullable String localeHint) {

        Optional<Profile> existing = repository.find(user);

        if (existing.isPresent()) {

            Profile profile = existing.get();

            if (!profile.chatId().equals(chatId)) {
                profile = profile.withChatId(chatId, now());
                repository.save(profile);
            }

            return profile;

        }

        Instant now = now();
        Locale locale = localeResolver.resolve(localeHint);
        Profile profile = new Profile(user, chatId, locale, schedulerConfig.defaultZone(),
                null, true, schedulerConfig.defaultNotifyTime(), null, now, now);

        repository.save(profile);
        return profile;

    }

    public Profile setBirthDate(Profile profile, eu.neydev.birthday.core.domain.BirthDate birthDate) {

        Profile updated = profile.withBirthDate(birthDate, now());
        updated = reschedule(updated);
        repository.save(updated);

        return updated;

    }

    public Profile setNotifyEnabled(Profile profile, boolean enabled) {

        Profile updated = profile.withNotifyEnabled(enabled, now());
        updated = enabled ? reschedule(updated) : updated.withNextReminderAt(null, now());
        repository.save(updated);

        return updated;

    }

    public Profile setLocale(Profile profile, Locale locale) {

        Profile updated = profile.withLocale(locale, now());
        repository.save(updated);

        return updated;

    }

    public Profile setZone(Profile profile, java.time.ZoneId zone) {

        Profile updated = reschedule(profile.withZone(zone, now()));
        repository.save(updated);

        return updated;

    }

    public Profile setNotifyTime(Profile profile, java.time.LocalTime time) {

        Profile updated = reschedule(profile.withNotifyTime(time, now()));
        repository.save(updated);

        return updated;

    }

    /**
     * Right to be forgotten: delete the profile and delivery log in one call.
     * Called by the /delete command and the Mini App button.
     */
    public void deleteData(@NotNull PlatformUser user) {

        repository.delete(user);
        deliveryLog.deleteFor(user);

    }

    /** The cursor is set only if there is a date and the subscription is enabled. */
    private Profile reschedule(Profile profile) {

        if (profile.birthDate() == null || !profile.notifyEnabled()) {
            return profile.withNextReminderAt(null, now());
        }

        return profile.withNextReminderAt(calculator.nextSlot(profile, now()), now());

    }

    public LocaleResolver localeResolver() {
        return localeResolver;
    }

    private Instant now() {
        return clock.instant();
    }

}
