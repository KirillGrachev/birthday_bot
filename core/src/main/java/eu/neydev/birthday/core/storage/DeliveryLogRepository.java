package eu.neydev.birthday.core.storage;

import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.domain.ReminderKind;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Delivery log: the guarantee of "do not greet twice and do not lose a day".
 * The unique key (platform, external_id, kind, local_date) makes a repeated
 * sending idempotent even after a crash between sending and updating the cursor.
 */
public interface DeliveryLogRepository {

    /**
     * @return {@code true} if the record was created (delivery not yet accounted for),
     *         {@code false} if it was already sent for this local date.
     */
    boolean markSent(@NotNull PlatformUser user,
                     @NotNull ReminderKind kind,
                     @NotNull LocalDate localDate,
                     @NotNull Instant sentAt);

    boolean wasSent(@NotNull PlatformUser user,
                    @NotNull ReminderKind kind,
                    @NotNull LocalDate localDate);

    /**
     * Full deletion of a user's delivery log (privacy/GDPR):
     * called together with deleting the profile.
     */
    void deleteFor(@NotNull PlatformUser user);

    /**
     * Removes the rows with a local date strictly before {@code cutoff}. The log is a
     * deduplication key for the current season, not an archive: without a prune it grows
     * by one row per user, day and reminder kind forever.
     *
     * @return the number of removed rows
     */
    int pruneBefore(@NotNull LocalDate cutoff);

}
