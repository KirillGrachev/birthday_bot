package eu.neydev.birthday.core.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Folding a keyboard into the geometry a platform accepts: rows merge until they fit, and
 * not one button is lost on the way.
 *
 * <p>Platforms answer an oversized keyboard by refusing the whole message (VK: error 911
 * "buttons contain too much rows", Discord: an action row limit the SDK enforces before
 * the request even leaves), so a keyboard that is one row too tall costs the reader the
 * entire screen. Folding keeps every action reachable, which is the difference between a
 * slightly denser menu and a dead one.
 *
 * <p>The order of the folds matters for readability. The two bottom rows become one
 * first: in the pickers that is the page counter and the footer under it, so the content
 * grid stays intact. Then the narrowest pair of one-button rows re-pairs, because the
 * phone-width rule of the core puts a long caption on a row of its own and a platform
 * would rather show two of them side by side. Rows of any shape merge last, narrowest
 * adjacent pair first. A keyboard that cannot be folded is returned as it is: the
 * platform then refuses it, and the dispatcher reports that honestly instead of retrying.
 */
public final class KeyboardFitter {

    private KeyboardFitter() {
    }

    public static List<List<InlineKeyboard.KeyboardButton>> fit(
            List<List<InlineKeyboard.KeyboardButton>> rows, KeyboardLimits limits) {
        return fit(rows, limits.maxRows(), limits.maxButtonsInRow());
    }

    public static List<List<InlineKeyboard.KeyboardButton>> fit(
            List<List<InlineKeyboard.KeyboardButton>> rows, int maxRows, int maxButtonsInRow) {

        if (rows.size() <= maxRows) {
            return rows;
        }

        List<List<InlineKeyboard.KeyboardButton>> fitted = new ArrayList<>(rows);
        foldBottomRows(fitted, maxButtonsInRow);

        while (fitted.size() > maxRows) {

            if (!mergeNarrowestAdjacent(fitted, true, maxButtonsInRow)
                    && !mergeNarrowestAdjacent(fitted, false, maxButtonsInRow)) {
                break;
            }

        }

        return fitted;

    }

    private static void foldBottomRows(List<List<InlineKeyboard.KeyboardButton>> rows,
                                       int maxButtonsInRow) {

        if (rows.size() < 2) {
            return;
        }

        List<InlineKeyboard.KeyboardButton> above = rows.get(rows.size() - 2);
        List<InlineKeyboard.KeyboardButton> last = rows.get(rows.size() - 1);

        if (above.size() + last.size() > maxButtonsInRow) {
            return;
        }

        List<InlineKeyboard.KeyboardButton> merged = new ArrayList<>(above);
        merged.addAll(last);

        rows.set(rows.size() - 2, List.copyOf(merged));
        rows.remove(rows.size() - 1);

    }

    private static boolean mergeNarrowestAdjacent(List<List<InlineKeyboard.KeyboardButton>> rows,
                                                  boolean singleButtonRowsOnly,
                                                  int maxButtonsInRow) {

        int best = -1;
        int bestWidth = Integer.MAX_VALUE;

        for (int i = 0; i + 1 < rows.size(); i++) {

            List<InlineKeyboard.KeyboardButton> left = rows.get(i);
            List<InlineKeyboard.KeyboardButton> right = rows.get(i + 1);

            if (left.size() + right.size() > maxButtonsInRow) {
                continue;
            }

            if (singleButtonRowsOnly && (left.size() != 1 || right.size() != 1)) {
                continue;
            }

            int width = captionWidth(left) + captionWidth(right);

            if (width < bestWidth) {
                bestWidth = width;
                best = i;
            }

        }

        if (best < 0) {
            return false;
        }

        List<InlineKeyboard.KeyboardButton> merged = new ArrayList<>(rows.get(best));
        merged.addAll(rows.get(best + 1));

        rows.set(best, List.copyOf(merged));
        rows.remove(best + 1);

        return true;

    }

    private static int captionWidth(List<InlineKeyboard.KeyboardButton> row) {

        int width = 0;

        for (InlineKeyboard.KeyboardButton button : row) {
            width += button.label().length();
        }

        return width;

    }

}
