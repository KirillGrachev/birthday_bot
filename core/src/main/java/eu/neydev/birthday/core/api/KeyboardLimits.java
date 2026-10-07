package eu.neydev.birthday.core.api;

/**
 * The keyboard geometry a platform accepts, and how much of a picker fits one page.
 *
 * <p>A mapper can fold rows that do not fit, and {@link KeyboardFitter} does exactly that
 * without dropping a single button. A picker it cannot fold: the number of choices on a
 * page decides how many buttons arrive at all, and only the core knows how many
 * navigation buttons surround them. So the pickers are built to the budget of the
 * platform they travel to, and the mappers fold whatever still overflows.
 *
 * <p>These are the platforms' own numbers, from their published limits and from the
 * errors they answer with: VK refuses an inline keyboard of seven rows with error 911,
 * Discord stops at five action rows, a WhatsApp list stops at ten rows, a Viber keyboard
 * at twenty-four. Each mapper carries the same numbers as constants of its own, next to
 * the code that enforces them, and the geometry gate in the app module fails the build
 * when a mapper and this table drift apart.
 *
 * @param maxRows            rows one keyboard may carry
 * @param maxButtonsInRow    buttons one row may carry
 * @param maxButtons         buttons one keyboard may carry in total, however they are
 *                           arranged. On most platforms this is the product of the two
 *                           above, but VK publishes a tighter one for inline keyboards:
 *                           ten buttons whole, and a keyboard past it is refused with
 *                           error 911 ("keyboard contains too much buttons"). No fold
 *                           can repair that - a fold rearranges buttons, it never drops
 *                           one - so the pickers page to the budget instead.
 * @param pickerItemsPerPage choices on one picker page a platform is comfortable with;
 *                           {@link #pickerItems} cuts it back to what the button budget
 *                           leaves once the navigation around the choices is paid for
 * @param labelChars         caption length the platform still shows whole
 */
public record KeyboardLimits(int maxRows, int maxButtonsInRow, int maxButtons,
                             int pickerItemsPerPage, int labelChars) {

    /**
     * The widest geometry we ship (Telegram's), and the default of the pickers wherever
     * no platform is known yet: a test, the web app, a keyboard built before the profile.
     */
    public static final KeyboardLimits WIDEST = new KeyboardLimits(100, 8, 100, 10, 64);

    public static KeyboardLimits of(Platform platform) {

        return switch (platform) {
            case TELEGRAM -> WIDEST;
            // Six rows of five would be thirty buttons, and VK still stops an inline
            // keyboard at ten of them: the total is the limit that bites here, and it is
            // what pages the pickers down to six choices and five zones.
            case VK -> new KeyboardLimits(6, 5, 10, 10, 40);
            case DISCORD -> new KeyboardLimits(5, 5, 25, 10, 80);
            case SLACK -> new KeyboardLimits(50, 25, 1250, 10, 75);
            // A list message is a flat column: ten rows of one choice each, opened by a
            // button of twenty characters. Five choices a page leave room for the
            // navigation row, the free-text zone prompt and the way back.
            case WHATSAPP -> new KeyboardLimits(10, 1, 10, 5, 24);
            case VIBER -> new KeyboardLimits(24, 6, 144, 10, 30);
        };

    }

    /**
     * The choices one picker page holds once {@code chrome} navigation buttons surround
     * them: the page counter, the arrows, the way back, and whatever else the picker
     * adds. The tightest of the two budgets wins, so a platform that counts buttons
     * whole (VK) pages fewer choices than a platform that only counts rows (Telegram),
     * while a platform comfortable with either keeps the page it was drawn with.
     *
     * @param chrome buttons on the page that are not choices
     */
    public int pickerItems(int chrome) {
        return Math.max(1, Math.min(pickerItemsPerPage, maxButtons - chrome));
    }

}
