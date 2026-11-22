package za.co.neroland.nerolink.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.prefs.PrefsStore;

/** NeroLink's Core export: the player's own devices and prefs, never a hash, device id or UUID. */
class NeroLinkDataExportTest {

    @Test
    void nothingStoredExportsNull() {
        UUID player = UUID.randomUUID();
        assertNull(NeroLinkDataExport.document(new TokenStore().devicesOf(player), new PrefsStore().notifications(player)));
    }

    @Test
    void exportsOnlyNamesTimesCountAndPrefs() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();
        tokens.issue(null, player, "phone");
        tokens.issue(null, other, "someone else's tablet");
        prefs.setNotifications(player, Map.of("alerts", true));

        JsonObject doc = NeroLinkDataExport.document(tokens.devicesOf(player), prefs.notifications(player));
        String json = doc.toString();

        assertEquals(1, doc.get("pairedDevices").getAsInt());
        assertEquals("phone", doc.getAsJsonArray("devices").get(0).getAsJsonObject().get("deviceName").getAsString());
        assertEquals(true, doc.getAsJsonObject("notificationPrefs").get("alerts").getAsBoolean());
        TokenStore.Device device = tokens.devicesOf(player).get(0);
        assertFalse(json.contains(device.tokenHash()), "never the token hash");
        assertFalse(json.contains(device.deviceId()), "never the device id");
        assertFalse(json.contains(player.toString()), "never the player's UUID");
        assertFalse(json.contains("tablet"), "never another player's device");
    }
}
