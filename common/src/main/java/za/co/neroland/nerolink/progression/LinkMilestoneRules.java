package za.co.neroland.nerolink.progression;

import java.util.Map;

/**
 * Pure decisions for {@link LinkMilestones}, kept free of game classes so they can be unit tested.
 * Every input is data NeroLink already holds; nothing here adds a stored counter.
 */
public final class LinkMilestoneRules {

    /** Live paired devices a player needs at once for {@link LinkMilestones.Milestone#TWO_DEVICES}. */
    public static final int TWO_DEVICES_THRESHOLD = 2;

    private LinkMilestoneRules() {
    }

    /** Whether the player's current live device count earns the two-device milestone. */
    public static boolean earnsTwoDevices(int liveDevices) {
        return liveDevices >= TWO_DEVICES_THRESHOLD;
    }

    /** Whether a notification-preference update turns at least one category on. */
    public static boolean enablesAlerts(Map<String, Boolean> categories) {
        if (categories == null) {
            return false;
        }
        for (Boolean on : categories.values()) {
            if (Boolean.TRUE.equals(on)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an API response status counts as a served/successful request. */
    public static boolean succeeded(int status) {
        return status >= 200 && status < 300;
    }
}
