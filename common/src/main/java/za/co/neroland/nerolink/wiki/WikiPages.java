package za.co.neroland.nerolink.wiki;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import za.co.neroland.nerolink.NeroLinkCommon;

/**
 * Loads a bundled set of wiki pages from the classpath and shapes them into the
 * <b>WIKI CONTRACT v1</b> the companion app browses. Reusable by any built-in that ships
 * markdown docs (today: {@link za.co.neroland.nerolink.coremodule.CoreModule}, which serves
 * NeroLink's own wiki), so the contract lives in exactly one place.
 *
 * <p>The page list comes from a generated {@code index.json} sitting alongside the {@code .md}
 * files ({@code {"pages":[{"slug","title"},...]}}) — classpath directory listing is unreliable
 * across loaders/jars, so the build ({@code generateWikiResources}) writes the index from the
 * single source of truth ({@code wiki/*.md}). Page {@code content} is the raw markdown of
 * {@code <base><slug>.md}. A {@code slug} not in the index is never read from disk, which also
 * guards against path-traversal via a crafted {@code page} param.
 *
 * <p>All content here is <b>public</b> (no personal data); {@code snapshot} ignores the player id.
 */
public final class WikiPages {

    /** One page's identity in the index. */
    public record Page(String slug, String title) {
    }

    private final String base;
    private final List<Page> pages;
    private final Map<String, Page> bySlug;

    /**
     * @param classpathBase absolute classpath directory holding {@code index.json} and the
     *                      {@code <slug>.md} files, e.g. {@code "/assets/nerolink/wiki/"}
     */
    public WikiPages(String classpathBase) {
        this.base = classpathBase.endsWith("/") ? classpathBase : classpathBase + "/";
        this.pages = loadIndex(this.base);
        Map<String, Page> map = new LinkedHashMap<>();
        for (Page p : pages) {
            map.put(p.slug(), p);
        }
        this.bySlug = map;
    }

    /** The page index (immutable, in index.json order). */
    public List<Page> pages() {
        return pages;
    }

    private static List<Page> loadIndex(String base) {
        List<Page> out = new ArrayList<>();
        try (InputStream in = WikiPages.class.getResourceAsStream(base + "index.json")) {
            if (in == null) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] wiki index not found at {}index.json", base);
                return List.of();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("pages");
            if (arr != null) {
                for (var el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String slug = o.get("slug").getAsString();
                    String title = o.has("title") && !o.get("title").isJsonNull()
                            ? o.get("title").getAsString() : slug.replace('-', ' ');
                    out.add(new Page(slug, title));
                }
            }
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] failed to load wiki index from {}", base, e);
        }
        return List.copyOf(out);
    }

    /** Raw markdown for a page, or empty if the slug is unknown (or the resource is missing). */
    public Optional<String> content(String slug) {
        if (!bySlug.containsKey(slug)) {
            // Unknown slug: never touch the classpath (path-traversal guard).
            return Optional.empty();
        }
        try (InputStream in = WikiPages.class.getResourceAsStream(base + slug + ".md")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] failed to read wiki page {}", slug, e);
            return Optional.empty();
        }
    }

    /**
     * Shape a WIKI CONTRACT v1 response: an INDEX when {@code params} carries no {@code page},
     * else the named PAGE (or an {@code error:"unknown page"} object for an unknown slug).
     *
     * @param modId the module id to stamp on the response (e.g. {@code "core"})
     * @param title the human title for the index (e.g. {@code "NeroLink"})
     */
    public JsonObject snapshot(String modId, String title, Map<String, String> params) {
        String page = params == null ? null : params.get("page");
        if (page == null || page.isBlank()) {
            return index(modId, title);
        }
        return page(modId, page);
    }

    /** The INDEX object: {@code {mod,title,pages:[{slug,title}],asOf}}. */
    public JsonObject index(String modId, String title) {
        JsonObject out = new JsonObject();
        out.addProperty("mod", modId);
        out.addProperty("title", title);
        JsonArray arr = new JsonArray();
        for (Page p : pages) {
            JsonObject o = new JsonObject();
            o.addProperty("slug", p.slug());
            o.addProperty("title", p.title());
            arr.add(o);
        }
        out.add("pages", arr);
        out.addProperty("asOf", System.currentTimeMillis());
        return out;
    }

    /**
     * The PAGE object: {@code {mod,slug,title,format:"markdown",content,asOf}}; for an unknown
     * slug, {@code {mod,slug,error:"unknown page",asOf}}.
     */
    public JsonObject page(String modId, String slug) {
        Optional<String> content = content(slug);
        if (content.isEmpty()) {
            JsonObject out = new JsonObject();
            out.addProperty("mod", modId);
            out.addProperty("slug", slug);
            out.addProperty("error", "unknown page");
            out.addProperty("asOf", System.currentTimeMillis());
            return out;
        }
        Page p = bySlug.get(slug);
        JsonObject out = new JsonObject();
        out.addProperty("mod", modId);
        out.addProperty("slug", slug);
        out.addProperty("title", p != null ? p.title() : slug.replace('-', ' '));
        out.addProperty("format", "markdown");
        out.addProperty("content", content.get());
        out.addProperty("asOf", System.currentTimeMillis());
        return out;
    }
}
