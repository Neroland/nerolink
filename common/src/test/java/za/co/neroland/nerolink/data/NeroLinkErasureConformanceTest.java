package za.co.neroland.nerolink.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * POPIA/GDPR conformance for NeroLink's player-keyed stores, run through Core's reusable
 * {@link ErasureConformance} harness: plain JVM, no game bootstrap, {@code null} server. The
 * production eraser ({@code NeroLinkCommon.eraseBridgeData}) needs a live server to fetch the
 * SavedData stores and refresh Core's backups, so the eraser registered here binds the same
 * {@code forget} calls to bare {@link TokenStore} / {@link PrefsStore} instances. The backup refresh
 * is a {@code SavedDataRecovery.backupNow} call with no logic of its own; the in-game half is
 * covered by runtime verification ({@code /neroland data erase}). Every test uses random UUIDs
 * and never logs them.
 */
class NeroLinkErasureConformanceTest {

    private final List<PlayerDataEraser> registered = new ArrayList<>();

    private void register(PlayerDataEraser eraser) {
        registered.add(eraser);
        PlayerDataErasure.register(eraser);
    }

    @AfterEach
    void unregisterErasers() {
        registered.forEach(PlayerDataErasure::unregister);
        registered.clear();
    }

    @Test
    void erasurePurgesTokensAndPrefs() {
        UUID player = UUID.randomUUID();
        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();

        // Seed, so the probes are not vacuous: two paired devices and an opted-in category.
        tokens.issue(null, player, "phone");
        tokens.issue(null, player, "tablet");
        prefs.setNotifications(player, Map.of("alerts", true));
        assertEquals(2, tokens.devicesOf(player).size());

        register((server, uuid) -> {
            tokens.forget(uuid);
            prefs.forget(uuid);
        });

        ErasureConformance.create()
                .probe("nerolink:tokens", uuid -> !tokens.devicesOf(uuid).isEmpty())
                .probe("nerolink:prefs", uuid -> !prefs.notifications(uuid).isEmpty())
                .verify(null, player);

        assertEquals(0, tokens.deviceCount());
        assertTrue(tokens.isDirty(), "the erased token store must be saved");
        assertTrue(prefs.isDirty(), "the erased prefs store must be saved");
    }

    @Test
    void erasureTargetsOnlyTheRequestedPlayer() {
        UUID erased = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();
        tokens.issue(null, erased, "phone");
        tokens.issue(null, retained, "phone");
        prefs.setNotifications(erased, Map.of("alerts", true));
        prefs.setNotifications(retained, Map.of("alerts", true));

        tokens.forget(erased);
        prefs.forget(erased);

        assertTrue(tokens.devicesOf(erased).isEmpty());
        assertEquals(1, tokens.devicesOf(retained).size(), "another player's device must survive");
        assertFalse(prefs.isEnabled(erased, "alerts"));
        assertTrue(prefs.isEnabled(retained, "alerts"), "another player's prefs must survive");
    }

    @Test
    void erasingAPlayerNobodyHasHeardOfIsHarmless() {
        TokenStore tokens = new TokenStore();
        PrefsStore prefs = new PrefsStore();
        tokens.forget(UUID.randomUUID());
        prefs.forget(UUID.randomUUID());
        assertEquals(0, tokens.deviceCount());
        assertFalse(tokens.isDirty(), "a no-op erasure must not dirty the store");
        assertFalse(prefs.isDirty(), "a no-op erasure must not dirty the store");
    }
}
