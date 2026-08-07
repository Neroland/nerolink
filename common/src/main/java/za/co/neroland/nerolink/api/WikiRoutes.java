package za.co.neroland.nerolink.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.http.ApiErrors;
import za.co.neroland.nerolink.http.ApiResponse;

/**
 * The in-app <b>wiki</b> routes. Any present module whose {@link LinkModuleInfo#dataSections()}
 * contains {@code "wiki"} is browsable: the bridge calls its {@link LinkSnapshotProvider}'s
 * {@code wiki} section per the WIKI CONTRACT v1 (no {@code page} param &rarr; index; {@code page=<slug>}
 * &rarr; page). This keeps the feature fully mod-agnostic — a mod opts in purely by advertising the
 * section, with no bridge change. NeroLink's own (and Core's) wiki is served by the built-in
 * {@code core} module (see {@link za.co.neroland.nerolink.coremodule.CoreModule}).
 *
 * <p>Wiki content is <b>public</b> (no personal data), but these run inside the same authenticated,
 * rate-limited pipeline as every other read. Providers are invoked on the server thread by the
 * caller ({@link ApiDispatcher} marshals via {@code onServerThread}).
 */
public final class WikiRoutes {

    /** Modules pinned to the front of the aggregate index, in this order, when present. */
    private static final List<String> PINNED_FIRST = List.of("core", "nerolink");

    private WikiRoutes() {
    }

    /**
     * {@code GET /api/v1/wiki} — the aggregate index across every present module that exposes a
     * {@code wiki} section. Order: {@code core} then {@code nerolink} first (if present), then the
     * rest by id. Modules that error or return nothing are skipped gracefully.
     *
     * @return {@code {mods:[{mod,title,pages:[...]}],asOf}}
     */
    public static ApiResponse aggregateIndex(UUID player) {
        Map<String, LinkModuleInfo> wikiModules = new LinkedHashMap<>();
        for (LinkModuleInfo info : NeroLinkRegistry.modules()) {
            if (info.dataSections().contains("wiki")) {
                wikiModules.put(info.moduleId(), info);
            }
        }

        List<String> order = new ArrayList<>();
        for (String id : PINNED_FIRST) {
            if (wikiModules.containsKey(id)) {
                order.add(id);
            }
        }
        List<String> rest = new ArrayList<>();
        for (String id : wikiModules.keySet()) {
            if (!order.contains(id)) {
                rest.add(id);
            }
        }
        Collections.sort(rest);
        order.addAll(rest);

        JsonArray mods = new JsonArray();
        for (String id : order) {
            Optional<LinkSnapshotProvider> provider = NeroLinkRegistry.snapshotProvider(id);
            if (provider.isEmpty()) {
                continue;
            }
            try {
                JsonObject idx = provider.get().snapshot(player, "wiki", Map.of());
                if (idx == null || !idx.has("pages") || idx.has("error")) {
                    continue; // module exposes wiki but returned nothing usable — skip.
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("mod", idx.has("mod") && !idx.get("mod").isJsonNull()
                        ? idx.get("mod").getAsString() : id);
                entry.addProperty("title", idx.has("title") && !idx.get("title").isJsonNull()
                        ? idx.get("title").getAsString() : id);
                entry.add("pages", idx.get("pages"));
                mods.add(entry);
            } catch (Exception e) {
                NeroLinkCommon.LOGGER.warn("[NeroLink] wiki index error for module {}", id, e);
            }
        }

        JsonObject data = new JsonObject();
        data.add("mods", mods);
        data.addProperty("asOf", System.currentTimeMillis());
        return ApiResponse.ok(data);
    }

    /**
     * {@code GET /api/v1/wiki/{moduleId}} — one module's index. {@code 404 MODULE_ABSENT} if the
     * module isn't present or doesn't expose a {@code wiki} section.
     */
    public static ApiResponse moduleIndex(UUID player, String moduleId) {
        Optional<LinkSnapshotProvider> provider = requireWiki(moduleId);
        if (provider.isEmpty()) {
            return ApiResponse.error(404, ApiErrors.MODULE_ABSENT, "module has no wiki: " + moduleId);
        }
        try {
            JsonObject idx = provider.get().snapshot(player, "wiki", Map.of());
            return ApiResponse.ok(idx == null ? new JsonObject() : idx);
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] wiki index error for module {}", moduleId, e);
            return ApiResponse.error(500, ApiErrors.INTERNAL, "wiki index failed");
        }
    }

    /**
     * {@code GET /api/v1/wiki/{moduleId}/{slug}} — one page. {@code 404 MODULE_ABSENT} if the module
     * isn't present / has no {@code wiki} section; {@code 404 NOT_FOUND} if the slug is unknown.
     */
    public static ApiResponse modulePage(UUID player, String moduleId, String slug) {
        Optional<LinkSnapshotProvider> provider = requireWiki(moduleId);
        if (provider.isEmpty()) {
            return ApiResponse.error(404, ApiErrors.MODULE_ABSENT, "module has no wiki: " + moduleId);
        }
        try {
            JsonObject page = provider.get().snapshot(player, "wiki", Map.of("page", slug));
            if (page == null || page.has("error")) {
                return ApiResponse.error(404, ApiErrors.NOT_FOUND, "unknown wiki page: " + slug);
            }
            return ApiResponse.ok(page);
        } catch (Exception e) {
            NeroLinkCommon.LOGGER.warn("[NeroLink] wiki page error for {}/{}", moduleId, slug, e);
            return ApiResponse.error(500, ApiErrors.INTERNAL, "wiki page failed");
        }
    }

    /** The provider for {@code moduleId}, but only if the module is present AND advertises {@code wiki}. */
    private static Optional<LinkSnapshotProvider> requireWiki(String moduleId) {
        Optional<LinkModuleInfo> info = NeroLinkRegistry.module(moduleId);
        if (info.isEmpty() || !info.get().dataSections().contains("wiki")) {
            return Optional.empty();
        }
        return NeroLinkRegistry.snapshotProvider(moduleId);
    }
}
