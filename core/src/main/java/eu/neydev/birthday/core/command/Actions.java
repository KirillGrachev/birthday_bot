package eu.neydev.birthday.core.command;

/**
 * Canonical action identifiers (callback / modal / form).
 * Short and stable: VK has a payload limit, Telegram - 64 bytes for callback_data.
 */
public final class Actions {

    public static final String SET_DATE = "sd";
    public static final String TOGGLE_NOTIFY = "tn";
    public static final String DAYS_TO = "dt";
    public static final String DAYS_SINCE = "ds";
    /** The second counting mode of the "days since" screen: since the last anniversary. */
    public static final String DAYS_SINCE_LAST = "dl";
    public static final String SETTINGS = "st";
    public static final String BACK = "bk";
    public static final String SET_TIME = "tm";
    public static final String SET_ZONE = "zn";
    public static final String LANG_PREFIX = "lg:";
    /** Opens the language picker (the settings menu button); choices use {@link #LANG_PREFIX}. */
    public static final String LANG_MENU = "lm";
    /** A page of the language picker: {@code lp:3} is the fourth page (zero-based). */
    public static final String LANG_PAGE_PREFIX = "lp:";
    /** Opens the full menu as a fresh message: the quiet keyboard's only button. */
    public static final String MENU_OPEN = "mo";
    /** Opens the zone picker with the text prompt instead of buttons. */
    public static final String ZONE_MANUAL = "zi";
    /** A zone choice: {@code zp:Europe/Moscow}. */
    public static final String ZONE_PICK_PREFIX = "zp:";
    /** A page of the zone picker: {@code zg:2} is the third page (zero-based). */
    public static final String ZONE_PAGE_PREFIX = "zg:";
    public static final String CANCEL = "cx";
    public static final String RELOAD = "ar";
    public static final String NOOP = "np";
    public static final String ABOUT = "ab";
    public static final String DELETE = "dd";
    public static final String DELETE_YES = "dy";
    public static final String DELETE_NO = "dn";

    /** Form fields (Discord modal, web app). */
    public static final String FORM_SET_DATE = "f_sd";
    public static final String FIELD_DATE = "date";

    private Actions() {
    }

    public static boolean isLang(String actionId) {
        return actionId != null && actionId.startsWith(LANG_PREFIX);
    }

    public static String langOf(String actionId) {
        return actionId.substring(LANG_PREFIX.length());
    }

    public static boolean isZonePick(String actionId) {
        return actionId != null && actionId.startsWith(ZONE_PICK_PREFIX);
    }

    public static String zoneOf(String actionId) {
        return actionId.substring(ZONE_PICK_PREFIX.length());
    }

    public static boolean isZonePage(String actionId) {
        return actionId != null && actionId.startsWith(ZONE_PAGE_PREFIX);
    }

    /** The page number of a picker action; anything unparsable opens the first page. */
    public static int zonePageOf(String actionId) {
        try {
            return Integer.parseInt(actionId.substring(ZONE_PAGE_PREFIX.length()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static boolean isLangPage(String actionId) {
        return actionId != null && actionId.startsWith(LANG_PAGE_PREFIX);
    }

    /** The page number of a picker action; anything unparsable opens the first page. */
    public static int langPageOf(String actionId) {
        try {
            return Integer.parseInt(actionId.substring(LANG_PAGE_PREFIX.length()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

}
