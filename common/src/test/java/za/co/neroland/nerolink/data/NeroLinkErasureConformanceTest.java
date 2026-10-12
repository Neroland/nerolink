package za.co.neroland.nerolink.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.data.ErasureConformance;
import za.co.neroland.nerolandcore.data.PlayerDataEraser;
import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolink.auth.TokenStore;
import za.co.neroland.nerolink.prefs.PrefsStore;

/**
 * POPIA/GDPR <b>erasure conformance</b> for NeroLink, run through Core's reusable
 * {@link ErasureConformance} harness.
 *
 * <p>NeroLink is the ecosystem's authentication surface: {@link TokenStore} holds one row per
 * paired device (hashed bearer token + owning player UUID) and {@link PrefsStore} holds per-player
 * notification categories. The failure this test exists to catch is the silent one — an eraser
 * that is registered, reports success, and leaves the rows on disk. The harness makes that
 * mechanical, and additionally proves that a failing eraser elsewhere in the fan-out cannot stop
 * NeroLink's from running.
 *
 * <p>Plain JVM, modelled on NeroFactions' equivalent: both stores are constructed directly
 * ({@code get(server)} needs a live server), and the eraser registered here mirrors the production
 * registration in {@link za.co.neroland.nerolink.NeroLinkCommon} minus the two pieces that need a
 * live server — the {@code SavedDataRecovery.backupNow} refresh and the bridge-side teardown
 * (pairing codes, WebSocket disconnect, relay tombstone). The {@code MinecraftServer} argument is
 * pass-through and unused.
 *
 * <p>Privacy note: every test uses a random UUID and never logs it. The harness's isolation pass
 * deliberately logs one "Data eraser ... failed" warning per run — that is the assertion working.
 */
class NeroLinkErasureConformanceTest {

    /** Erasers this test registered, removed again so one test cannot leak into the next. */
    private final List<PlayerDataEraser> registered = new ArrayList<>();

    @AfterEach
    void restoreRegistry() {
        registered.forEach(PlayerDataErasure::unregister);
        registered.clear();
    }

    private void register(PlayerDataEraser eraser) {
        registered.add(eraser);
        PlayerDataErasure.register(eraser);
    }

    /** Seeds every player-keyed section both stores have, so neither probe is vacuous. */
    private static void seed(TokenStore tokens, PrefsStore prefs, UUID player) {
        // issue(...) ignores its server argument; it only mints a token and stores the hash.
        tokens.issue(null, player, "phone");
        tokens.issue(null, player, "tablet");
        prefs.setNotifications(player, Map.of("quests", true, "colony_alerts", false));
    }

    private static boolean hasTokens(TokenStore tokens, UUID player) {
        return !tokens.devicesOf(player).isEmpty();
    }

    private static boolean hasPrefs(PrefsStore prefs, UUID player) {
        return !prefs.notifications(player).isEmpty();
    }

    @Test
    void erasurePurgesEverythingNeroLinkStores() {
        UUID player = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();

        seed(tokens, prefs, player);
        seed(tokens, prefs, bystander);

        register((server, uuid) -> {
            tokens.forget(uuid);
            prefs.forget(uuid);
        });

        ErasureConformance.create()
                .probe("nerolink:device_tokens", uuid -> hasTokens(tokens, uuid))
                .probe("nerolink:notification_prefs", uuid -> hasPrefs(prefs, uuid))
                .verify(null, player);

        // A second player's pairing must survive the request untouched — erasure is per-subject.
        assertTrue(hasTokens(tokens, bystander), "the bystander's paired devices must survive");
        assertTrue(hasPrefs(prefs, bystander), "the bystander's notification prefs must survive");
    }

    @Test
    void conformanceReportIsCleanAndCarriesNoPlayerIdentity() {
        UUID player = UUID.randomUUID();

        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();
        seed(tokens, prefs, player);

        register((server, uuid) -> {
            tokens.forget(uuid);
            prefs.forget(uuid);
        });

        ErasureConformance.Report report = ErasureConformance.create()
                .probe("nerolink:device_tokens", uuid -> hasTokens(tokens, uuid))
                .probe("nerolink:notification_prefs", uuid -> hasPrefs(prefs, uuid))
                .run(null, player);

        assertTrue(report.passed(), report.describe());
        assertTrue(report.retainedSubsystems().isEmpty(), report.describe());
        assertTrue(report.erasersRegistered() >= 1, "our eraser must be in the fan-out");
        assertFalse(report.describe().contains(player.toString()),
                "the report must never carry the player UUID (it is personal data)");
    }

    @Test
    void erasureToleratesAPlayerItHasNeverHeardOf() {
        // The retention sweep and the harness's isolation pass both invoke every eraser for a
        // player with no rows; NeroLink's must be a clean no-op rather than throwing.
        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();

        register((server, uuid) -> {
            tokens.forget(uuid);
            prefs.forget(uuid);
        });

        PlayerDataErasure.erase(null, UUID.randomUUID());

        assertFalse(hasTokens(tokens, UUID.randomUUID()), "no rows should have appeared");
    }
}
