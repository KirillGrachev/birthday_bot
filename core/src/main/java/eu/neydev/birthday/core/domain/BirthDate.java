package eu.neydev.birthday.core.domain;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.Year;

/**
 * A birth date with an OPTIONAL year: users often do not want to give the year,
 * and for countdown reminders it is not needed at all. The year is required only for
 * "how many days SINCE the birthday" and the age in the greeting.
 *
 * @param month 1-12;
 * @param day   1-31 (valid for the month, 29.02 allowed);
 * @param year  the year or {@code null} if not specified.
 */
public record BirthDate(int month, int day, @Nullable Integer year) {

    public BirthDate {

        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Month out of range 1-12: " + month);
        }

        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("Day out of range 1-31: " + day);
        }

        if (day > LocalDate.of(2004, month, 1).lengthOfMonth() && !(month == 2 && day == 29)) {
            throw new IllegalArgumentException("Invalid day for month: " + day + "." + month);
        }

    }

    public static BirthDate of(LocalDate date) {
        return new BirthDate(date.getMonthValue(), date.getDayOfMonth(), date.getYear());
    }

    public static BirthDate ofMonthDay(int month, int day) {
        return new BirthDate(month, day, null);
    }

    public boolean hasYear() {
        return year != null;
    }

    public boolean isLeapDay() {
        return month == 2 && day == 29;
    }

    /** The date of the nearest birthday in the given year, honoring the 29 February policy. */
    public LocalDate anniversaryIn(int year, @NotNull LeapDayPolicy policy) {

        if (isLeapDay() && !Year.isLeap(year)) {
            return switch (policy) {
                case LAST_OF_FEBRUARY -> LocalDate.of(year, 2, 28);
                case FIRST_OF_MARCH -> LocalDate.of(year, 3, 1);
            };
        }

        return LocalDate.of(year, month, day);

    }

    /** The full date if the year is known. */
    public @Nullable LocalDate fullDate() {
        return year == null ? null : LocalDate.of(year, month, day);
    }

    /**
     * The full date when the year is known, or a failure - for the paths that already
     * checked {@link #hasYear()} and need a non-null date without a second null branch.
     */
    public @NotNull LocalDate requireFullDate() {

        LocalDate full = fullDate();
        if (full == null) {
            throw new IllegalStateException("Birth date has no year: " + this);
        }

        return full;

    }

    /** Human-readable representation without a year: {@code 29.02}. */
    public String toMonthDayString() {
        return "%02d.%02d".formatted(day, month);
    }

    @Override
    public String toString() {
        return hasYear() ? "%04d-%02d-%02d".formatted(year, month, day) : "----%02d-%02d".formatted(month, day);
    }

}
