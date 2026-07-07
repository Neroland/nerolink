package za.co.neroland.nerolink.platform;

import java.util.List;

/**
 * Loader-agnostic snapshot of the installed Neroland mods, populated once per JVM by the active
 * loader entry point (Fabric / Forge / NeoForge) right after mod discovery is complete. The
 * {@code core} module reads it for the {@code mods} section so the companion app can render a
 * mods overview (and drive update checks) without knowing the loader.
 *
 * <p>Deliberately dumb: the loader collects the list (filtering ids that start with {@code nero},
 * case-insensitive, including the bridge itself) and calls {@link #set(String, List)}. This holder
 * neither sorts nor filters — the {@code core} module sorts by id when it serialises. The data is
 * server-wide public metadata (a mods list is not personal data), identical for every player.
 */
public final class InstalledMods {

    /** One installed mod: its id, display name and version string, all loader-supplied. */
    public record Entry(String id, String name, String version) {
    }

    private static volatile String loader = "unknown";
    private static volatile List<Entry> entries = List.of();

    private InstalledMods() {
    }

    /**
     * Record the running loader name ({@code "fabric"} / {@code "forge"} / {@code "neoforge"}) and
     * the collected mod entries. Called exactly once at init; the values are immutable snapshots.
     */
    public static void set(String loaderName, List<Entry> mods) {
        loader = (loaderName == null || loaderName.isBlank()) ? "unknown" : loaderName;
        entries = (mods == null) ? List.of() : List.copyOf(mods);
    }

    /** The running loader name, or {@code "unknown"} if not yet set. */
    public static String loader() {
        return loader;
    }

    /** The collected mod entries (unsorted, immutable). Empty until a loader populates it. */
    public static List<Entry> entries() {
        return entries;
    }
}
