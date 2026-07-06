package za.co.neroland.nerolink.ws;

import java.util.UUID;

/**
 * The bridge's view of one live WebSocket connection. Implemented by the Netty WS handler; the
 * {@link WebSocketHub} depends only on this interface so its subscription/delta logic stays
 * loader- and transport-neutral (and testable without a socket).
 */
public interface WsConnection {

    /** The authenticated player this socket is scoped to. */
    UUID player();

    /** A stable id for this connection (the device id — one WS per token). */
    String connectionId();

    /** Send a text frame (already-serialized JSON). Called from the server thread; must be async-safe. */
    void send(String json);

    /** Close the connection (e.g. on erasure or duplicate). */
    void close();
}
