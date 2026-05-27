package mage.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local WebSocket endpoint the Electron UI connects to. Each socket gets its own
 * {@link BridgeSession} (its own XMage Session). Binds to loopback only — this is
 * a local IPC channel between the Electron app and the headless Java side, not a
 * public server.
 */
public class BridgeServer extends WebSocketServer {

    private final Map<WebSocket, BridgeSession> sessions = new ConcurrentHashMap<>();

    public BridgeServer(String host, int port) {
        super(new InetSocketAddress(host, port));
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new BridgeSession(conn));
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("bridge", "mage-bridge");
        conn.send(hello.toString());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        BridgeSession s = sessions.get(conn);
        if (s == null) {
            return;
        }
        try {
            JsonObject cmd = JsonParser.parseString(message).getAsJsonObject();
            s.handle(cmd);
        } catch (Throwable t) {
            JsonObject err = new JsonObject();
            err.addProperty("type", "error");
            err.addProperty("where", "parse");
            err.addProperty("message", String.valueOf(t.getMessage()));
            conn.send(err.toString());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        BridgeSession s = sessions.remove(conn);
        if (s != null) {
            s.doDisconnect();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // a single socket error must not take down the server
        System.err.println("[bridge] socket error: " + ex);
    }

    @Override
    public void onStart() {
        System.out.println("[bridge] listening on " + getAddress());
    }
}
