package za.co.neroland.nerolink.ws;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkEvent;
import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.http.Json;

/**
 * Fans Core's {@link za.co.neroland.nerolandcore.link.LinkEventBus} out to connected WebSocket
 * clients, and owns the per-connection topic subscriptions.
 *
 * <p><b>Scoping (POPIA/GDPR).</b> A player-scoped {@link LinkEvent} is delivered only to that
 * player's own sockets; a broadcast event goes to everyone. No socket ever sees another
 * player's data.
 *
 * <p><b>Batching.</b> Deltas are coalesced per {@code (connection, topic)} and flushed at most
 * once per second by a scheduler, exactly as the spec requires ("the bridge batches deltas per
 * topic per second"). A {@code snapshot:true} frame is sent immediately on subscription so the
 * client starts consistent.
 *
 * <p><b>Threading.</b> {@link #onLinkEvent(LinkEvent)} runs on whatever thread published the
 * event (usually the server thread). Frame sends go through {@link WsConnection#send(String)},
 * which the Netty handler makes async-safe.
 */
public final class WebSocketHub {

    /** Topics that mirror snapshot endpoints, e.g. {@code core.energy}, {@code nerologistics.drones}. */
    private static final class Subscriber {
        final WsConnection connection;
        final Set<String> topics = ConcurrentHashMap.newKeySet();
        /** Pending coalesced deltas per topic (last-write-wins into an array). */
        final Map<String, JsonArray> pending = new ConcurrentHashMap<>();

        Subscriber(WsConnection connection) {
            this.connection = connection;
        }
    }

    /** connectionId -> subscriber. One connection per token (device). */
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private volatile MinecraftServer server;
    private java.util.concurrent.ScheduledExecutorService flusher;

    /** Bind the hub to a running server and start the 1 Hz delta flush + 30s ping loop. */
    public void attach(MinecraftServer server) {
        this.server = server;
        this.flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nerolink-ws-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        flusher.scheduleAtFixedRate(this::pingAll, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Register a freshly upgraded socket (enforces one connection per device). */
    public void register(WsConnection connection) {
        Subscriber previous = subscribers.put(connection.connectionId(), new Subscriber(connection));
        if (previous != null) {
            // One WS per token: drop the older connection.
            previous.connection.close();
        }
    }

    /** Drop a closed socket. */
    public void unregister(String connectionId) {
        subscribers.remove(connectionId);
    }

    /**
     * Handle an inbound control frame: {@code {"op":"sub","topics":[...]}} /
     * {@code {"op":"unsub","topics":[...]}} / {@code {"op":"ping"}}. On subscribe, an immediate
     * {@code snapshot:true} frame is sent for each newly-subscribed topic.
     */
    public void onControlFrame(String connectionId, JsonObject frame) {
        Subscriber sub = subscribers.get(connectionId);
        if (sub == null || frame == null || !frame.has("op")) {
            return;
        }
        String op = frame.get("op").getAsString();
        switch (op) {
            case "sub" -> {
                if (frame.has("topics") && frame.get("topics").isJsonArray()) {
                    frame.getAsJsonArray("topics").forEach(t -> {
                        String topic = t.getAsString();
                        if (sub.topics.add(topic)) {
                            sendSnapshot(sub, topic);
                        }
                    });
                }
            }
            case "unsub" -> {
                if (frame.has("topics") && frame.get("topics").isJsonArray()) {
                    frame.getAsJsonArray("topics").forEach(t -> sub.topics.remove(t.getAsString()));
                }
            }
            case "ping" -> sub.connection.send("{\"op\":\"pong\"}");
            default -> { /* ignore unknown ops (forward-compat) */ }
        }
    }

    /** Bridge a Core link event to interested sockets as a coalesced delta. */
    public void onLinkEvent(LinkEvent event) {
        String topic = event.moduleId() + "." + event.topic();
        JsonObject delta = event.payload() == null ? new JsonObject() : event.payload();
        for (Subscriber sub : subscribers.values()) {
            if (!sub.topics.contains(topic)) {
                continue;
            }
            // Player-scoped events only to the owning player's sockets; broadcasts to all.
            if (!event.isBroadcast() && !sub.connection.player().equals(event.playerId())) {
                continue;
            }
            sub.pending.computeIfAbsent(topic, k -> new JsonArray()).add(delta);
        }
    }

    /** Send an immediate consistent-start snapshot frame for a topic. */
    private void sendSnapshot(Subscriber sub, String topic) {
        int dot = topic.indexOf('.');
        if (dot <= 0 || server == null) {
            return;
        }
        String moduleId = topic.substring(0, dot);
        String section = topic.substring(dot + 1);
        MinecraftServer srv = server;
        srv.execute(() -> {
            Optional<LinkSnapshotProvider> provider = NeroLinkRegistry.snapshotProvider(moduleId);
            JsonObject data = provider
                    .map(p -> {
                        try {
                            return p.snapshot(sub.connection.player(), section, Map.of());
                        } catch (Exception e) {
                            NeroLinkCommon.LOGGER.warn("[NeroLink] ws snapshot error {}", topic, e);
                            return new JsonObject();
                        }
                    })
                    .orElseGet(JsonObject::new);
            JsonObject frame = new JsonObject();
            frame.addProperty("topic", topic);
            frame.addProperty("t", System.currentTimeMillis());
            frame.addProperty("snapshot", true);
            frame.add("data", data == null ? new JsonObject() : data);
            sub.connection.send(Json.toString(frame));
        });
    }

    /** Flush all pending coalesced deltas (called at 1 Hz). */
    private void flush() {
        long now = System.currentTimeMillis();
        for (Subscriber sub : subscribers.values()) {
            if (sub.pending.isEmpty()) {
                continue;
            }
            var it = sub.pending.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                it.remove();
                JsonObject frame = new JsonObject();
                frame.addProperty("topic", entry.getKey());
                frame.addProperty("t", now);
                frame.add("delta", entry.getValue());
                try {
                    sub.connection.send(Json.toString(frame));
                } catch (Exception e) {
                    NeroLinkCommon.LOGGER.debug("[NeroLink] ws send failed; dropping", e);
                }
            }
        }
    }

    private void pingAll() {
        for (Subscriber sub : subscribers.values()) {
            try {
                sub.connection.send("{\"op\":\"ping\",\"t\":" + System.currentTimeMillis() + "}");
            } catch (Exception ignored) {
                // socket likely gone; the handler will unregister on close
            }
        }
    }

    /** Close and drop all of a player's sockets (used on erasure). */
    public void disconnectPlayer(UUID player) {
        subscribers.values().removeIf(sub -> {
            if (sub.connection.player().equals(player)) {
                sub.connection.close();
                return true;
            }
            return false;
        });
    }

    /** Stop the flush loop and drop all sockets (bridge stop). */
    public void shutdown() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        subscribers.values().forEach(sub -> sub.connection.close());
        subscribers.clear();
    }
}
