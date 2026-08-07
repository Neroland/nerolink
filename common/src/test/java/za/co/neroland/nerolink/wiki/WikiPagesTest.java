package za.co.neroland.nerolink.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WikiPages}, the reusable classpath-backed wiki loader + WIKI CONTRACT v1
 * shaper. Runs against a deterministic fixture bundled under
 * {@code common/src/test/resources/assets/nerolink/wiki-test/} (an {@code index.json} plus two
 * markdown pages), so it is independent of the build-time bundling of NeroLink's real wiki.
 */
class WikiPagesTest {

    private static final String BASE = "/assets/nerolink/wiki-test/";

    private static WikiPages pages() {
        return new WikiPages(BASE);
    }

    @Test
    void loadsIndexFromClasspath() {
        WikiPages wiki = pages();
        assertEquals(2, wiki.pages().size(), "fixture index has two pages");
        assertTrue(wiki.pages().stream().anyMatch(p -> p.slug().equals("Home")));
        assertTrue(wiki.pages().stream().anyMatch(p -> p.slug().equals("Getting-Started")
                && p.title().equals("Getting Started")));
    }

    @Test
    void contentReturnsRawMarkdownForKnownSlug() {
        WikiPages wiki = pages();
        assertTrue(wiki.content("Home").isPresent());
        assertTrue(wiki.content("Home").get().contains("Welcome to the test wiki fixture"));
    }

    @Test
    void contentEmptyForUnknownSlug() {
        assertTrue(pages().content("Nope").isEmpty());
    }

    @Test
    void contentGuardsAgainstPathTraversal() {
        // A crafted slug outside the index must never be read from the classpath.
        assertTrue(pages().content("../secret").isEmpty());
        assertTrue(pages().content("../../build.gradle").isEmpty());
    }

    @Test
    void indexShapeMatchesContract() {
        JsonObject idx = pages().index("core", "NeroLink");
        assertEquals("core", idx.get("mod").getAsString());
        assertEquals("NeroLink", idx.get("title").getAsString());
        assertTrue(idx.has("asOf"));
        assertEquals(2, idx.getAsJsonArray("pages").size());
        JsonObject first = idx.getAsJsonArray("pages").get(0).getAsJsonObject();
        assertTrue(first.has("slug"));
        assertTrue(first.has("title"));
    }

    @Test
    void pageShapeMatchesContract() {
        JsonObject page = pages().page("core", "Home");
        assertEquals("core", page.get("mod").getAsString());
        assertEquals("Home", page.get("slug").getAsString());
        assertEquals("markdown", page.get("format").getAsString());
        assertTrue(page.get("content").getAsString().contains("Welcome"));
        assertFalse(page.has("error"));
    }

    @Test
    void unknownPageYieldsErrorObject() {
        JsonObject page = pages().page("core", "Nope");
        assertEquals("unknown page", page.get("error").getAsString());
        assertFalse(page.has("content"));
    }

    @Test
    void snapshotRoutesByPageParam() {
        WikiPages wiki = pages();
        // No page param -> INDEX.
        assertTrue(wiki.snapshot("core", "NeroLink", Map.of()).has("pages"));
        // page param -> PAGE.
        assertEquals("markdown",
                wiki.snapshot("core", "NeroLink", Map.of("page", "Home")).get("format").getAsString());
    }
}
