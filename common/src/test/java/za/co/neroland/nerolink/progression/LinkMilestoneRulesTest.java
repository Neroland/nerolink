package za.co.neroland.nerolink.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LinkMilestoneRulesTest {

    @Test
    void twoDevicesNeedsTwoLiveDevices() {
        assertFalse(LinkMilestoneRules.earnsTwoDevices(0));
        assertFalse(LinkMilestoneRules.earnsTwoDevices(1));
        assertTrue(LinkMilestoneRules.earnsTwoDevices(2));
        assertTrue(LinkMilestoneRules.earnsTwoDevices(5));
    }

    @Test
    void alertsNeedOneCategoryOn() {
        assertFalse(LinkMilestoneRules.enablesAlerts(null));
        assertFalse(LinkMilestoneRules.enablesAlerts(Map.of()));
        assertFalse(LinkMilestoneRules.enablesAlerts(Map.of("nerospace", false)));
        Map<String, Boolean> mixed = new LinkedHashMap<>();
        mixed.put("nerospace", false);
        mixed.put("nerotech", true);
        assertTrue(LinkMilestoneRules.enablesAlerts(mixed));
    }

    @Test
    void onlySuccessStatusesCount() {
        assertTrue(LinkMilestoneRules.succeeded(200));
        assertFalse(LinkMilestoneRules.succeeded(403));
        assertFalse(LinkMilestoneRules.succeeded(404));
        assertFalse(LinkMilestoneRules.succeeded(500));
    }
}
