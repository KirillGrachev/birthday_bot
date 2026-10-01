package eu.neydev.birthday.core.domain;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * All birthday arithmetic in one place: pure, deterministic, covered by tests.
 * "Today" is always passed explicitly (in the user's local date) - no
 * hidden {@code LocalDate.now()} inside the domain.
 */
public final class BirthdayMath {

    private BirthdayMath() {
    }

    /**
     * How many days are left until the next birthday.
     * If today is the birthday - 0.
     */
    public static long daysUntil(@NotNull BirthDate birthDate,
                                 @NotNull LocalDate today,
                                 @NotNull LeapDayPolicy policy) {
        return ChronoUnit.DAYS.between(today, nextAnniversary(birthDate, today, policy));
    }

    /** The date of the nearest (today or future) birthday. */
    public static LocalDate nextAnniversary(@NotNull BirthDate birthDate,
                                            @NotNull LocalDate today,
                                            @NotNull LeapDayPolicy policy) {

        LocalDate candidate = birthDate.anniversaryIn(today.getYear(), policy);

        if (candidate.isBefore(today)) {
            candidate = birthDate.anniversaryIn(today.getYear() + 1, policy);
        }

        return candidate;

    }

    /** Is today the birthday? */
    public static boolean isBirthdayToday(@NotNull BirthDate birthDate,
                                          @NotNull LocalDate today,
                                          @NotNull LeapDayPolicy policy) {
        return nextAnniversary(birthDate, today, policy).isEqual(today);
    }

    /** How many days have passed SINCE the birthday (full age in days). Requires a year. */
    public static long daysSince(@NotNull BirthDate birthDate, @NotNull LocalDate today) {
        LocalDate full = birthDate.requireFullDate();
        return ChronoUnit.DAYS.between(full, today);
    }

    /**
     * Days passed since the MOST RECENT anniversary - the second counting mode of the
     * "days since" screen. On the birthday itself the reference is the previous year's
     * anniversary, so a full year is reported rather than zero.
     */
    public static long daysSinceLastAnniversary(@NotNull BirthDate birthDate,
                                                 @NotNull LocalDate today,
                                                 @NotNull LeapDayPolicy policy) {

        LocalDate last = birthDate.anniversaryIn(today.getYear(), policy);

        if (last.isAfter(today)) {
            last = birthDate.anniversaryIn(today.getYear() - 1, policy);
        }

        return ChronoUnit.DAYS.between(last, today);

    }

    /** How many years turn at the next birthday. Requires a year. */
    public static int turningAge(@NotNull BirthDate birthDate,
                                 @NotNull LocalDate today,
                                 @NotNull LeapDayPolicy policy) {

        if (!birthDate.hasYear()) {
            throw new IllegalStateException("turningAge requires a birth year");
        }

        return nextAnniversary(birthDate, today, policy).getYear() - birthDate.year();

    }

    /** Full age as of today (how many years have passed). Requires a year. */
    public static int currentAge(@NotNull BirthDate birthDate,
                                 @NotNull LocalDate today,
                                 @NotNull LeapDayPolicy policy) {
        int turning = turningAge(birthDate, today, policy);
        return nextAnniversary(birthDate, today, policy).isAfter(today) ? turning - 1 : turning;
    }

}
