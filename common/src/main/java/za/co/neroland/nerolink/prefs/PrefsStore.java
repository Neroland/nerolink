package za.co.neroland.nerolink.prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * Per-player notification preferences (the {@code prefs/notifications} endpoints). A flat
 * map of {@code category -> enabled}; unknown categories default off, matching the design's
 * "notification categories are opt-in per category" rule. Stored per player UUID.
 *
 * <p>POPIA/GDPR: non-personal boolean flags keyed by UUID; {@link #forget(UUID)} drops a
 * player's prefs and is wired into Core's erasure hook.
 */
public final class PrefsStore extends SavedData {

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroLinkCommon.MOD_ID, "prefs");

    public static final SavedDataType<PrefsStore> TYPE =
            new SavedDataType<>(ID, PrefsStore::new, codec(), null);

    /** player -> (category -> enabled). */
    private final Map<UUID, Map<String, Boolean>> byPlayer = new LinkedHashMap<>();

    public PrefsStore() {
    }

    public static PrefsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** A player's notification category flags (a sorted copy; empty if never set). */
    public Map<String, Boolean> notifications(UUID player) {
        Map<String, Boolean> prefs = byPlayer.get(player);
        return prefs == null ? Map.of() : new TreeMap<>(prefs);
    }

    /** Replace a player's category flags wholesale (PUT semantics). */
    public void setNotifications(UUID player, Map<String, Boolean> categories) {
        Map<String, Boolean> clean = new LinkedHashMap<>();
        categories.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                clean.put(k.trim(), v);
            }
        });
        if (clean.isEmpty()) {
            byPlayer.remove(player);
        } else {
            byPlayer.put(player, clean);
        }
        setDirty();
    }

    /** Whether a player has opted into a notification category (default false). */
    public boolean isEnabled(UUID player, String category) {
        Map<String, Boolean> prefs = byPlayer.get(player);
        return prefs != null && Boolean.TRUE.equals(prefs.get(category));
    }

    /** POPIA/GDPR erasure. */
    public void forget(UUID player) {
        if (byPlayer.remove(player) != null) {
            setDirty();
        }
    }

    // --- persistence -----------------------------------------------------------------

    private record Entry(String key, boolean value) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("k").forGetter(Entry::key),
                Codec.BOOL.fieldOf("v").forGetter(Entry::value)
        ).apply(inst, Entry::new));
    }

    private record Row(String player, List<Entry> prefs) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("player").forGetter(Row::player),
                Entry.CODEC.listOf().fieldOf("prefs").forGetter(Row::prefs)
        ).apply(inst, Row::new));
    }

    private static Codec<PrefsStore> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                Row.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(PrefsStore::rows)
        ).apply(inst, PrefsStore::fromRows));
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        byPlayer.forEach((uuid, prefs) -> {
            List<Entry> entries = new ArrayList<>();
            prefs.forEach((k, v) -> entries.add(new Entry(k, v)));
            out.add(new Row(uuid.toString(), entries));
        });
        return out;
    }

    private static PrefsStore fromRows(List<Row> rows) {
        PrefsStore store = new PrefsStore();
        for (Row row : rows) {
            UUID uuid;
            try {
                uuid = UUID.fromString(row.player());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Map<String, Boolean> prefs = new LinkedHashMap<>();
            for (Entry e : row.prefs()) {
                prefs.put(e.key(), e.value());
            }
            store.byPlayer.put(uuid, prefs);
        }
        return store;
    }
}
