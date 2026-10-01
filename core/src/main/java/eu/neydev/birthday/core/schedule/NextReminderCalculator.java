package eu.neydev.birthday.core.schedule;

import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.Profile;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Computing the persistent {@code next_reminder_at} cursor:
 * the next slot of the user's local delivery time.
 *
 * <p>The cursor is stored in the DB, so a process restart does not shift the schedule -
 * this is a fundamental difference from the old bot, where "a day" was counted
 * a fixed timer delay from the start moment and drifted/were skipped.
 */
public final class NextReminderCalculator {

    private final AppConfig.Scheduler config;

    public NextReminderCalculator(AppConfig.Scheduler config) {
        this.config = config;
    }

    /** The nearest delivery slot: today if the local time has not come yet, otherwise tomorrow. */
    public Instant nextSlot(@NotNull Profile profile, @NotNull Instant now) {
        return nextSlot(profile.zone(), profile.notifyTime(), now);
    }

    public Instant nextSlot(@NotNull ZoneId zone, @NotNull java.time.LocalTime time, @NotNull Instant now) {

        ZonedDateTime nowInZone = now.atZone(zone);
        ZonedDateTime today = nowInZone.toLocalDate().atTime(time).atZone(zone);

        if (!today.toInstant().isAfter(now)) {
            today = today.plusDays(1);
        }

        return today.toInstant();

    }

    /**
     * The local date the cursor was scheduled for. The scheduler needs it
     * to distinguish a "today" tick from a catch-up one after downtime.
     */
    public LocalDate scheduledLocalDate(@NotNull Profile profile) {

        if (profile.nextReminderAt() == null) {
            return profile.today();
        }

        return profile.nextReminderAt().atZone(profile.zone()).toLocalDate();

    }

    public AppConfig.Scheduler config() {
        return config;
    }

}
