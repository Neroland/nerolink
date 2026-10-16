package za.co.neroland.nerolink.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.link.LinkEvent;

/** Mod-supplied push copy: per-field fallback, caps, deep-link regex, raised-only alerts, shadow. */
class NotifyCopyTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String INST = "6f1c2d3e-0000-4000-8000-000000000abc";

    private static LinkEvent event(String topic, JsonObject payload) {
        return new LinkEvent("nerosecurity", topic, PLAYER, payload, 0L);
    }

    private static LinkEvent withNotify(JsonObject notify) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "LASER_TRIPPED");
        p.add(NotifyCopy.FIELD, notify);
        return event("events", p);
    }

    private static String linkFor(String raw, String serverId) {
        JsonObject n = new JsonObject();
        n.addProperty("deepLink", raw);
        return NotifyCopy.resolve(withNotify(n), serverId).deepLink();
    }

    @Test
    void noNotifyIsTheFixed100Copy() {
        NotifyCopy.Copy c = NotifyCopy.resolve(event("events", new JsonObject()), "abc");
        assertEquals("NeroLink — nerosecurity", c.title());
        assertEquals("events update", c.body());
        assertNull(c.deepLink());
        assertFalse(c.rich());
        assertEquals(c, NotifyCopy.fallback(event("events", new JsonObject())));
        // a non-object _notify and a null payload are ignored the same way
        JsonObject p = new JsonObject();
        p.addProperty(NotifyCopy.FIELD, "Laser grid tripped");
        assertEquals(NotifyCopy.fallback(event("events", p)), NotifyCopy.resolve(event("events", p), "abc"));
        assertEquals(NotifyCopy.fallback(event("events", null)), NotifyCopy.resolve(event("events", null), "abc"));
    }

    @Test
    void fullNotifyIsCarriedThroughWithServerSubstituted() {
        JsonObject n = new JsonObject();
        n.addProperty("title", "Laser grid tripped");
        n.addProperty("body", "Orbital Lab (+3 more)");
        n.addProperty("deepLink", "nerolink://server/{server}/security/" + INST);
        NotifyCopy.Copy c = NotifyCopy.resolve(withNotify(n), "abc");
        assertEquals("Laser grid tripped", c.title());
        assertEquals("Orbital Lab (+3 more)", c.body());
        assertEquals("nerolink://server/abc/security/" + INST, c.deepLink());
        assertTrue(NotifyCopy.DEEP_LINK.matcher(c.deepLink()).matches());
        assertTrue(c.rich());
    }

    @Test
    void capsControlCharsAndPerFieldIndependence() {
        JsonObject n = new JsonObject();
        n.addProperty("title", "t".repeat(500));
        n.addProperty("body", "  b\u0000o\u0007d\ny\t " + "x".repeat(500));
        NotifyCopy.Copy c = NotifyCopy.resolve(withNotify(n), "abc");
        assertEquals(NotifyCopy.TITLE_MAX, c.title().length());
        assertEquals(NotifyCopy.BODY_MAX, c.body().length());
        assertTrue(c.body().startsWith("body x"));
        assertTrue(c.body().chars().noneMatch(ch -> ch < 0x20));

        JsonObject bad = new JsonObject();
        bad.addProperty("title", 42);
        bad.addProperty("body", "Orbital Lab");
        NotifyCopy.Copy d = NotifyCopy.resolve(withNotify(bad), "abc");
        assertEquals("NeroLink — nerosecurity", d.title());
        assertEquals("Orbital Lab", d.body());
        assertTrue(d.rich());

        JsonObject blank = new JsonObject();
        blank.addProperty("title", " \u0001 ");
        blank.add("body", new JsonObject());
        NotifyCopy.Copy e = NotifyCopy.resolve(withNotify(blank), "abc");
        assertEquals(NotifyCopy.fallback(withNotify(blank)), e);
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        JsonObject n = new JsonObject();
        n.addProperty("title", "a".repeat(NotifyCopy.TITLE_MAX - 1) + "🔒tail");
        String t = NotifyCopy.resolve(withNotify(n), "abc").title();
        assertEquals(NotifyCopy.TITLE_MAX - 1, t.length());
        assertFalse(Character.isHighSurrogate(t.charAt(t.length() - 1)));
    }

    @Test
    void deepLinkRejections() {
        assertEquals("nerolink://server/abc/security", linkFor("nerolink://server/abc/security", ""));
        assertEquals("nerolink://server/abc/security/a/b", linkFor("nerolink://server/abc/security/a/b", ""));
        assertNull(linkFor("https://server/abc/security/" + INST, "abc"));          // wrong scheme
        assertNull(linkFor("nerolink://server/abc/Security/" + INST, "abc"));       // uppercase module
        assertNull(linkFor("nerolink://server/abc/security/a/b/c", "abc"));         // three entity segments
        assertNull(linkFor("nerolink://server/abc/security/" + "a".repeat(300), "abc")); // too long
        assertNull(linkFor("nerolink://server/" + "a".repeat(64) + "/security/" + "b".repeat(64) + "/"
                + "c".repeat(64) + "x".repeat(40), "abc"));                         // > 256
        assertNull(linkFor("nerolink://server/{server}/security/" + INST, ""));     // placeholder, no id
        assertNull(linkFor("nerolink://server/{server}/security/" + INST, "  "));
        assertNull(linkFor("javascript:alert(1)", "abc"));
        assertNull(linkFor("nerolink://server/abc/security/javascript:x", "abc"));
        assertNull(linkFor("nerolink://server/abc/security/../x", "abc"));
        assertNull(linkFor("nerolink://server/abc/security/..", "abc"));
        assertNull(linkFor("nerolink://server/a b/security", "abc"));
        assertNull(linkFor("nerolink://server/{server}/security", "bad id"));      // substituted id must match too
        JsonObject n = new JsonObject();
        n.addProperty("deepLink", 7);
        assertNull(NotifyCopy.resolve(withNotify(n), "abc").deepLink());
    }

    @Test
    void deepLinkAloneIsNotRich() {
        JsonObject n = new JsonObject();
        n.addProperty("deepLink", "nerolink://server/{server}/security");
        NotifyCopy.Copy c = NotifyCopy.resolve(withNotify(n), "abc");
        assertFalse(c.rich());
        assertEquals("nerolink://server/abc/security", c.deepLink());
        assertEquals("NeroLink — nerosecurity", c.title());
    }

    @Test
    void alertsPushOnlyWhenRaised() {
        assertTrue(NotifyCopy.shouldPush(event("alerts", verb("raised"))));
        assertFalse(NotifyCopy.shouldPush(event("alerts", verb("acked"))));
        assertFalse(NotifyCopy.shouldPush(event("alerts", verb("snoozed"))));
        assertFalse(NotifyCopy.shouldPush(event("alerts", verb("evicted"))));
        assertTrue(NotifyCopy.shouldPush(event("alerts", new JsonObject())));
        assertTrue(NotifyCopy.shouldPush(event("alerts", null)));
        JsonObject numeric = new JsonObject();
        numeric.addProperty("event", 1);
        assertFalse(NotifyCopy.shouldPush(event("alerts", numeric)));
        assertTrue(NotifyCopy.shouldPush(event("events", verb("acked"))));
        assertTrue(NotifyCopy.shouldPush(event("drones", verb("evicted"))));
    }

    private static JsonObject verb(String v) {
        JsonObject p = new JsonObject();
        p.addProperty("event", v);
        return p;
    }

    @Test
    void richPushShadowsGenericPushForTwoSeconds() {
        NotifyCopy.RichPushShadow s = new NotifyCopy.RichPushShadow();
        assertFalse(s.shadowed(PLAYER, "nerosecurity", 1_000L));
        s.remember(PLAYER, "nerosecurity", 1_000L);
        assertTrue(s.shadowed(PLAYER, "nerosecurity", 1_001L));
        assertTrue(s.shadowed(PLAYER, "nerosecurity", 1_000L + NotifyCopy.RICH_SHADOW_MS - 1));
        assertFalse(s.shadowed(PLAYER, "nerosecurity", 1_000L + NotifyCopy.RICH_SHADOW_MS));
        assertFalse(s.shadowed(PLAYER, "nerocolonies", 1_001L));
        assertFalse(s.shadowed(UUID.randomUUID(), "nerosecurity", 1_001L));
    }

    @Test
    void shadowPrunesAndStaysBounded() {
        NotifyCopy.RichPushShadow s = new NotifyCopy.RichPushShadow();
        s.remember(PLAYER, "a", 0L);
        s.remember(PLAYER, "b", NotifyCopy.RichPushShadow.PRUNE_AFTER_MS + 1);
        assertEquals(1, s.size());
        long now = NotifyCopy.RichPushShadow.PRUNE_AFTER_MS * 10;
        for (int i = 0; i < NotifyCopy.RichPushShadow.MAX_ENTRIES + 10; i++) {
            s.remember(new UUID(0L, i), "m", now);
        }
        assertTrue(s.size() <= NotifyCopy.RichPushShadow.MAX_ENTRIES);
    }
}
