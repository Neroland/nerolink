package za.co.neroland.nerolink.relay;

import java.util.UUID;

import za.co.neroland.nerolink.ws.WsConnection;

/**
 * A {@link WsConnection} whose transport is not a real Netty socket but a companion-client
 * WebSocket multiplexed over the relay tunnel, identified by its relay {@code cid}. It lets the
 * {@link za.co.neroland.nerolink.ws.WebSocketHub} treat a relay-backed client exactly like a
 * locally-connected one: the hub's subscription, snapshot, delta-batching and player-scoping logic
 * is entirely unaware of the transport.
 *
 * <p>Frames the hub sends become {@code {t:"ws_msg", cid, data}} tunnel frames; {@link #close()}
 * becomes a {@code {t:"ws_close", cid, ...}} tunnel frame. The connection id is the token's device
 * id, so the hub's one-WS-per-token rule and the rate limiter's per-device bucket apply to relay
 * clients identically to local ones.
 */
public final class RelayWsConnection implements WsConnection {

    private final RelayClient client;
    private final String cid;
    private final UUID player;
    private final String deviceId;

    RelayWsConnection(RelayClient client, String cid, UUID player, String deviceId) {
        this.client = client;
        this.cid = cid;
        this.player = player;
        this.deviceId = deviceId;
    }

    @Override
    public UUID player() {
        return player;
    }

    @Override
    public String connectionId() {
        // Device id (one WS per token) — matches the local Netty-backed connection's contract.
        return deviceId;
    }

    @Override
    public void send(String json) {
        client.sendWsMsg(cid, json);
    }

    @Override
    public void close() {
        // Bridge-initiated close: tell the relay to drop the client and forget the cid locally.
        client.closeVirtual(cid, 1000, "closed");
    }
}
