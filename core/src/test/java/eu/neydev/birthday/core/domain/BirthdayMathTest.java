package eu.neydev.birthday.core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayMathTest {

    private static final LeapDayPolicy POLICY = LeapDayPolicy.LAST_OF_FEBRUARY;

    @Test
    void daysUntilSimple() {
        BirthDate birth = BirthDate.of(LocalDate.of(1990, 6, 15));
        assertThat(BirthdayMath.daysUntil(birth, LocalDate.of(2026, 6, 10), POLICY)).isEqualTo(5);
    }

    @Test
    void daysUntilZeroOnBirthday() {

        BirthDate birth = BirthDate.of(LocalDate.of(1990, 6, 15));
        LocalDate today = LocalDate.of(2026, 6, 15);

        assertThat(BirthdayMath.daysUntil(birth, today, POLICY)).isZero();
        assertThat(BirthdayMath.isBirthdayToday(birth, today, POLICY)).isTrue();

    }

    @Test
    void daysUntilWrapsToNextYear() {
        BirthDate birth = BirthDate.of(LocalDate.of(1990, 1, 5));
        assertThat(BirthdayMath.daysUntil(birth, LocalDate.of(2026, 12, 31), POLICY)).isEqualTo(5);
    }

    @Test
    void leapDayLastOfFebruaryPolicy() {

        BirthDate birth = BirthDate.ofMonthDay(2, 29);
        LocalDate anniversary = BirthdayMath.nextAnniversary(birth, LocalDate.of(2027, 1, 1), POLICY);
        assertThat(anniversary).isEqualTo(LocalDate.of(2027, 2, 28));

    }

    @Test
    void leapDayFirstOfMarchPolicy() {

        BirthDate birth = BirthDate.ofMonthDay(2, 29);
        LocalDate anniversary = BirthdayMath.nextAnniversary(
                birth, LocalDate.of(2027, 1, 1), LeapDayPolicy.FIRST_OF_MARCH);
        assertThat(anniversary).isEqualTo(LocalDate.of(2027, 3, 1));

    }

    @Test
    void leapDayKeptInLeapYear() {

        BirthDate birth = BirthDate.ofMonthDay(2, 29);
        LocalDate anniversary = BirthdayMath.nextAnniversary(birth, LocalDate.of(2028, 1, 1), POLICY);
        assertThat(anniversary).isEqualTo(LocalDate.of(2028, 2, 29));

    }

    @Test
    void daysSinceAndAge() {

        BirthDate birth = BirthDate.of(LocalDate.of(2000, 3, 5));
        LocalDate today = LocalDate.of(2026, 9, 30);

        assertThat(BirthdayMath.daysSince(birth, today))
                .isEqualTo(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2000, 3, 5), today));
        assertThat(BirthdayMath.currentAge(birth, today, POLICY)).isEqualTo(26);
        assertThat(BirthdayMath.turningAge(birth, today, POLICY)).isEqualTo(27);

    }

    @Test
    void daysSinceLastAnniversaryCountsFromTheMostRecentBirthday() {

        BirthDate birth = BirthDate.of(LocalDate.of(2000, 3, 5));

        // The day after the birthday: one day into the new year of life.
        assertThat(BirthdayMath.daysSinceLastAnniversary(birth, LocalDate.of(2026, 3, 6), POLICY))
                .isEqualTo(1);
        // The day before the next one: a full year minus one.
        assertThat(BirthdayMath.daysSinceLastAnniversary(birth, LocalDate.of(2027, 3, 4), POLICY))
                .isEqualTo(364);

    }

    @Test
    void daysSinceLastAnniversaryOnTheBirthdayIsZero() {

        BirthDate birth = BirthDate.of(LocalDate.of(2000, 3, 5));

        // The anniversary is today - nothing has passed since it yet.
        assertThat(BirthdayMath.daysSinceLastAnniversary(birth, LocalDate.of(2026, 3, 5), POLICY))
                .isZero();

    }

}
