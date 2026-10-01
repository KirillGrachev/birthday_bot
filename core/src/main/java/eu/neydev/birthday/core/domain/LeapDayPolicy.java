package eu.neydev.birthday.core.domain;

/**
 * The policy for celebrating 29 February in non-leap years.
 * Configured globally in {@code scheduler.leap-day-policy}.
 */
public enum LeapDayPolicy {

    LAST_OF_FEBRUARY,
    FIRST_OF_MARCH;

    public static LeapDayPolicy parse(String value) {

        for (LeapDayPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(value.replace('-', '_'))) {
                return policy;
            }
        }

        throw new IllegalArgumentException(
                "Unknown leap-day-policy: " + value + " (expected last-of-february | first-of-march)");

    }

}
