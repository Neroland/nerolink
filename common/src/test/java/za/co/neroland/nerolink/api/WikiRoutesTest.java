package za.co.neroland.nerolink.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.http.ApiErrors;
import za.co.neroland.nerolink.http.ApiResponse;
import za.co.neroland.nerolink.wiki.WikiPages;

/**
 * Tests {@link WikiRoutes}, the mod-agnostic in-app wiki aggregation + per-module/page routing.
 * Registers fake {@link LinkSnapshotProvider}s (backed by the test wiki fixture) into the shared
 * {@link NeroLinkRegistry}: a module is browsable purely by advertising a {@code "wiki"} data
 * section, so the routes are exercised with no game bootstrap.
 */
class WikiRoutesTest {

    private static final UUID PLAYER = UUID.randomUUID();

    /** A provider that serves the test wiki fixture under an arbitrary module id/title. */
    private static final class FakeWikiProvider implements LinkSnapshotProvider {
        private static final WikiPages FIXTURE = new WikiPages("/assets/nerolink/wiki-test/");
        private final String id;
        private final String title;

        FakeWikiProvider(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String moduleId() {
            return id;
        }

        @Override
        public int schemaVersion() {
            return 1;
        }

        @Override
        public List<String> sections() {
            return List.of("wiki");
        }

        @Override
        public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
            return FIXTURE.snapshot(id, title, params);
        }
    }

    private static void registerWiki(String id, String title) {
        FakeWikiProvider provider = new FakeWikiProvider(id, title);
        LinkModuleInfo info = new LinkModuleInfo(id, "1.0.0", 1, List.of("wiki"), List.of());
        NeroLinkRegistry.registerSnapshotProvider(provider, info);
    }

    @BeforeEach
    void setUp() {
        // Register a deterministic set: two pinned ids, two "other" ids, and one non-wiki module.
        registerWiki("core", "NeroLink");
        registerWiki("nerolink", "NeroLink");
        registerWiki("amod", "A Mod");
        registerWiki("zmod", "Z Mod");
        // A present module that does NOT expose wiki (must be excluded / 404).
        LinkModuleInfo noWiki = new LinkModuleInfo("nowiki", "1.0.0", 1, List.of("gates"), List.of());
        NeroLinkRegistry.registerSnapshotProvider(new FakeWikiProvider("nowiki", "No Wiki"), noWiki);
    }

    private static List<String> modIdsOf(ApiResponse response) {
        JsonObject data = response.body().getAsJsonObject("data");
        JsonArray mods = data.getAsJsonArray("mods");
        List<String> ids = new ArrayList<>();
        for (var el : mods) {
            ids.add(el.getAsJsonObject().get("mod").getAsString());
        }
        return ids;
    }

    @Test
    void aggregateIndexIncludesOnlyWikiModulesInPinnedOrder() {
        ApiResponse response = WikiRoutes.aggregateIndex(PLAYER);
        assertEquals(200, response.status());
        assertTrue(response.body().get("ok").getAsBoolean());

        List<String> ids = modIdsOf(response);
        assertTrue(ids.contains("core"));
        assertTrue(ids.contains("nerolink"));
        assertTrue(ids.contains("amod"));
        assertTrue(ids.contains("zmod"));
        assertFalse(ids.contains("nowiki"), "a module without a wiki section must be excluded");

        // Pinned first (core, nerolink), then the rest by id (amod < zmod).
        assertTrue(ids.indexOf("core") < ids.indexOf("nerolink"));
        assertTrue(ids.indexOf("nerolink") < ids.indexOf("amod"));
        assertTrue(ids.indexOf("amod") < ids.indexOf("zmod"));

        // Each entry carries the module's page list.
        JsonObject data = response.body().getAsJsonObject("data");
        for (var el : data.getAsJsonArray("mods")) {
            assertTrue(el.getAsJsonObject().has("pages"));
        }
    }

    @Test
    void moduleIndexReturnsPagesForWikiModule() {
        ApiResponse response = WikiRoutes.moduleIndex(PLAYER, "amod");
        assertEquals(200, response.status());
        JsonObject data = response.body().getAsJsonObject("data");
        assertEquals("amod", data.get("mod").getAsString());
        assertEquals("A Mod", data.get("title").getAsString());
        assertEquals(2, data.getAsJsonArray("pages").size());
    }

    @Test
    void moduleIndexForModuleWithoutWikiIs404() {
        ApiResponse response = WikiRoutes.moduleIndex(PLAYER, "nowiki");
        assertEquals(404, response.status());
        assertEquals(ApiErrors.MODULE_ABSENT, errorCode(response));
    }

    @Test
    void moduleIndexForAbsentModuleIs404() {
        ApiResponse response = WikiRoutes.moduleIndex(PLAYER, "definitely-absent-xyz");
        assertEquals(404, response.status());
        assertEquals(ApiErrors.MODULE_ABSENT, errorCode(response));
    }

    @Test
    void modulePageReturnsMarkdown() {
        ApiResponse response = WikiRoutes.modulePage(PLAYER, "amod", "Home");
        assertEquals(200, response.status());
        JsonObject data = response.body().getAsJsonObject("data");
        assertEquals("markdown", data.get("format").getAsString());
        assertTrue(data.get("content").getAsString().contains("Welcome"));
    }

    @Test
    void unknownSlugIs404NotFound() {
        ApiResponse response = WikiRoutes.modulePage(PLAYER, "amod", "Nope");
        assertEquals(404, response.status());
        assertEquals(ApiErrors.NOT_FOUND, errorCode(response));
    }

    @Test
    void pageOnModuleWithoutWikiIs404ModuleAbsent() {
        ApiResponse response = WikiRoutes.modulePage(PLAYER, "nowiki", "Home");
        assertEquals(404, response.status());
        assertEquals(ApiErrors.MODULE_ABSENT, errorCode(response));
    }

    private static String errorCode(ApiResponse response) {
        return response.body().getAsJsonObject("error").get("code").getAsString();
    }
}
