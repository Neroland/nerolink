package za.co.neroland.nerolink.privacy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.prefs.PrefsStore;

/**
 * NeroLink's section of Neroland Core's {@code /neroland data exportme} (Core 1.20.1), the read-side
 * twin of {@code NeroLinkCommon.eraseBridgeData}. It covers the two persistent stores that eraser
 * clears: the player's paired devices and their notification preferences.
 *
 * <p>Minimal by design: per device only the name the player gave it and when it was paired and last
 * seen, plus a count. Never a token hash, a device id or the player's UUID. Reads the stores only, so
 * it works for offline players. Writes and logs nothing.</p>
 */
public final class NeroLinkDataExport {

    private NeroLinkDataExport() {
    }

    /** The live export for {@code player}, or {@code null} when NeroLink holds nothing for them. */
    @Nullable
    public static JsonElement export(MinecraftServer server, UUID player) {
        return document(TokenStore.get(server).devicesOf(player), PrefsStore.get(server).notifications(player));
    }

    /** The pure core, for the plain-JVM tests. {@code null} when there are no devices and no preferences. */
    @Nullable
    static JsonObject document(List<TokenStore.Device> devices, Map<String, Boolean> notificationPrefs) {
        if (devices.isEmpty() && notificationPrefs.isEmpty()) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.addProperty("pairedDevices", devices.size());
        JsonArray rows = new JsonArray();
        for (TokenStore.Device d : devices) {
            JsonObject row = new JsonObject();
            row.addProperty("deviceName", d.deviceName());
            row.addProperty("pairedAt", d.createdAt());
            row.addProperty("lastSeenAt", d.lastSeenAt());
            rows.add(row);
        }
        root.add("devices", rows);
        JsonObject prefs = new JsonObject();
        notificationPrefs.forEach(prefs::addProperty);
        root.add("notificationPrefs", prefs);
        return root;
    }
}
