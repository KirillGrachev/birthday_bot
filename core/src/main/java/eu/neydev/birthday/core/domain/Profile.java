package eu.neydev.birthday.core.domain;

import eu.neydev.birthday.core.api.PlatformUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * A user profile. An immutable record; mutations - only via with-copies.
 *
 * <p>{@code chatId} is the platform's native dialog identifier (without prefixes:
 * the platform is already in {@link #user()}). Convenience accessors delegate
 * {@link DeliverySettings}, to keep the calls flat.
 */
public record Profile(@NotNull PlatformUser user,
                      @NotNull String chatId,
                      @NotNull Locale locale,
                      @NotNull DeliverySettings delivery,
                      @Nullable BirthDate birthDate,
                      @NotNull Instant createdAt,
                      @NotNull Instant updatedAt) {

    /** A flat constructor for compatibility and tests. */
    public Profile(PlatformUser user, String chatId, Locale locale, ZoneId zone,
                   BirthDate birthDate, boolean notifyEnabled, LocalTime notifyTime,
                   Instant nextReminderAt, Instant createdAt, Instant updatedAt) {
        this(user, chatId, locale,
                new DeliverySettings(zone, notifyEnabled, notifyTime, nextReminderAt, 0),
                birthDate, createdAt, updatedAt);
    }

    public ZoneId zone() {
        return delivery.zone();
    }

    public boolean notifyEnabled() {
        return delivery.notifyEnabled();
    }

    public LocalTime notifyTime() {
        return delivery.notifyTime();
    }

    public @Nullable Instant nextReminderAt() {
        return delivery.nextReminderAt();
    }

    public int attempts() {
        return delivery.attempts();
    }

    public Profile withBirthDate(BirthDate value, Instant now) {
        return new Profile(user, chatId, locale, delivery, value, createdAt, now);
    }

    public Profile withNotifyEnabled(boolean value, Instant now) {
        return new Profile(user, chatId, locale, delivery.withNotifyEnabled(value),
                birthDate, createdAt, now);
    }

    public Profile withLocale(Locale value, Instant now) {
        return new Profile(user, chatId, value, delivery, birthDate, createdAt, now);
    }

    public Profile withZone(ZoneId value, Instant now) {
        return new Profile(user, chatId, locale, delivery.withZone(value), birthDate, createdAt, now);
    }

    public Profile withNotifyTime(LocalTime value, Instant now) {
        return new Profile(user, chatId, locale, delivery.withNotifyTime(value),
                birthDate, createdAt, now);
    }

    public Profile withChatId(String value, Instant now) {
        return new Profile(user, value, locale, delivery, birthDate, createdAt, now);
    }

    public Profile withNextReminderAt(Instant value, Instant now) {
        return new Profile(user, chatId, locale, delivery.withNextReminderAt(value),
                birthDate, createdAt, now);
    }

    public Profile withAttempts(int value, Instant now) {
        return new Profile(user, chatId, locale, delivery.withAttempts(value),
                birthDate, createdAt, now);
    }

    /** The user's local "today" - all delivery is computed in their zone. */
    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /**
     * The user's local "today" at a given instant. Delivery and catch-up decisions must use
     * THIS form: the scheduler hands the authoritative {@code now} down the pipeline, and
     * mixing it with the system clock breaks exactly at midnight and after downtime -
     * the reminder kind, the message and the {@code delivery_log} dedup key all follow
     * the local date.
     */
    public LocalDate todayAt(Instant now) {
        return now.atZone(zone()).toLocalDate();
    }

}
