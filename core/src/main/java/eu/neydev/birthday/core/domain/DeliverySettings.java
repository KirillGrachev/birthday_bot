package eu.neydev.birthday.core.domain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Reminder delivery settings: extracted from the profile so the profile does not bloat
 * and so that schedule changes are visible as one immutable swap.
 *
 * @param zone           delivery time zone;
 * @param notifyEnabled  reminder subscription;
 * @param notifyTime     local delivery time;
 * @param nextReminderAt the UTC moment of the next reminder (persistent cursor);
 * @param attempts       the counter of failed attempts of the current delivery (in the DB, survives restart).
 */
public record DeliverySettings(@NotNull ZoneId zone,
                               boolean notifyEnabled,
                               @NotNull LocalTime notifyTime,
                               @Nullable Instant nextReminderAt,
                               int attempts) {

    public DeliverySettings {
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts cannot be negative");
        }
    }

    public DeliverySettings withZone(ZoneId value) {
        return new DeliverySettings(value, notifyEnabled, notifyTime, nextReminderAt, attempts);
    }

    public DeliverySettings withNotifyEnabled(boolean value) {
        return new DeliverySettings(zone, value, notifyTime, nextReminderAt, attempts);
    }

    public DeliverySettings withNotifyTime(LocalTime value) {
        return new DeliverySettings(zone, notifyEnabled, value, nextReminderAt, attempts);
    }

    public DeliverySettings withNextReminderAt(Instant value) {
        return new DeliverySettings(zone, notifyEnabled, notifyTime, value, attempts);
    }

    public DeliverySettings withAttempts(int value) {
        return new DeliverySettings(zone, notifyEnabled, notifyTime, nextReminderAt, value);
    }

}
