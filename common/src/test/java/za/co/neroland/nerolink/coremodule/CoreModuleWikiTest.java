package za.co.neroland.nerolink.coremodule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;

/**
 * End-to-end coverage for the built-in {@code core} module's {@code wiki} section, which serves
 * NeroLink's OWN wiki (title "NeroLink"). This exercises the real bundled resources —
 * {@code assets/nerolink/wiki/index.json} + the {@code .md} pages produced by the
 * {@code generateWikiResources} build step from the single source of truth ({@code wiki/*.md}) —
 * so it also validates the bundling wiring. The {@code wiki} section is public and needs no
 * running server, so it is invoked directly.
 */
class CoreModuleWikiTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private LinkSnapshotProvider core() {
        return NeroLinkRegistry.snapshotProvider(CoreModule.MODULE_ID).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        // Register (or restore) the real core module; other tests may have replaced it.
        CoreModule.register();
    }

    @Test
    void coreAdvertisesWikiSection() {
        LinkModuleInfo info = NeroLinkRegistry.module(CoreModule.MODULE_ID).orElseThrow();
        assertTrue(info.dataSections().contains("wiki"),
                "core must advertise a wiki section so the aggregate index picks it up");
    }

    @Test
    void wikiIndexServesNeroLinkOwnPages() {
        JsonObject idx = core().snapshot(PLAYER, "wiki", Map.of());
        assertEquals("core", idx.get("mod").getAsString());
        assertEquals("NeroLink", idx.get("title").getAsString());
        JsonArray pages = idx.getAsJsonArray("pages");
        assertTrue(pages.size() > 0, "the bundled wiki must expose at least one page");
        boolean hasHome = false;
        for (var el : pages) {
            if (el.getAsJsonObject().get("slug").getAsString().equals("Home")) {
                hasHome = true;
            }
        }
        assertTrue(hasHome, "the bundled wiki must include the Home page");
    }

    @Test
    void wikiPageServesMarkdownForKnownSlug() {
        JsonObject page = core().snapshot(PLAYER, "wiki", Map.of("page", "Home"));
        assertEquals("core", page.get("mod").getAsString());
        assertEquals("Home", page.get("slug").getAsString());
        assertEquals("markdown", page.get("format").getAsString());
        assertFalse(page.has("error"));
        assertTrue(page.get("content").getAsString().length() > 0);
    }

    @Test
    void wikiUnknownSlugYieldsErrorObject() {
        JsonObject page = core().snapshot(PLAYER, "wiki", Map.of("page", "No-Such-Page"));
        assertEquals("unknown page", page.get("error").getAsString());
        assertFalse(page.has("content"));
    }
}
