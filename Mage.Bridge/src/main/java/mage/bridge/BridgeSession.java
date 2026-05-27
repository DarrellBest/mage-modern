package mage.bridge;

import com.google.gson.JsonObject;
import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.view.TableView;
import org.java_websocket.WebSocket;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One bridge session = one Electron connection. Owns a real {@link SessionImpl}
 * (the XMage network/session layer) + a {@link HeadlessClient}, and translates
 * JSON commands from Electron into Session calls, while forwarding server-pushed
 * callbacks back to Electron as JSON. No game logic lives here — it is pure
 * protocol translation.
 */
public class BridgeSession {

    private final WebSocket socket;
    private final HeadlessClient client = new HeadlessClient();
    private final SessionImpl session = new SessionImpl(client);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bridge-session");
        t.setDaemon(true);
        return t;
    });

    public BridgeSession(WebSocket socket) {
        this.socket = socket;
        client.setCallbackSink(this::forwardCallback);
        client.setConnectionSink(this::forwardConnectionEvent);
    }

    // ---- outbound (bridge -> Electron) ----

    private void send(JsonObject msg) {
        if (socket.isOpen()) {
            socket.send(msg.toString());
        }
    }

    private void event(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        send(o);
    }

    private void error(String where, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("where", where);
        o.addProperty("message", message);
        send(o);
    }

    private void forwardConnectionEvent(HeadlessClient.ConnectionEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "connection");
        o.addProperty("kind", e.kind.name());
        o.addProperty("message", e.message);
        send(o);
    }

    private void forwardCallback(mage.interfaces.callback.ClientCallback cb) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "callback");
        o.addProperty("method", cb.getMethod().name());
        o.addProperty("messageId", cb.getMessageId());
        if (cb.getObjectId() != null) {
            o.addProperty("objectId", cb.getObjectId().toString());
        }
        String data = Json.tryToJson(cb.getData());
        if (data != null) {
            o.add("data", com.google.gson.JsonParser.parseString(data));
        } else {
            o.addProperty("dataError", true);
        }
        send(o);
    }

    // ---- inbound (Electron -> bridge) ----

    /** Dispatch one JSON command from Electron. Never throws. */
    public void handle(JsonObject cmd) {
        String c = cmd.has("cmd") ? cmd.get("cmd").getAsString() : "";
        try {
            switch (c) {
                case "connect":
                    doConnect(cmd);
                    break;
                case "getTables":
                    doGetTables();
                    break;
                case "sendPlayerBoolean":
                    session.sendPlayerBoolean(uuid(cmd, "gameId"), cmd.get("value").getAsBoolean());
                    break;
                case "sendPlayerInteger":
                    session.sendPlayerInteger(uuid(cmd, "gameId"), cmd.get("value").getAsInt());
                    break;
                case "sendPlayerString":
                    session.sendPlayerString(uuid(cmd, "gameId"), cmd.get("value").getAsString());
                    break;
                case "sendPlayerUUID":
                    session.sendPlayerUUID(uuid(cmd, "gameId"), uuid(cmd, "value"));
                    break;
                case "disconnect":
                    doDisconnect();
                    break;
                default:
                    error(c, "unknown command");
            }
        } catch (Throwable t) {
            error(c, String.valueOf(t.getMessage()));
        }
    }

    private void doConnect(JsonObject cmd) {
        final String host = cmd.get("host").getAsString();
        final int port = cmd.get("port").getAsInt();
        final String user = cmd.get("user").getAsString();
        final String pwd = cmd.has("password") ? cmd.get("password").getAsString() : "";
        worker.submit(() -> {
            try {
                Connection conn = new Connection();
                conn.setHost(host);
                conn.setPort(port);
                conn.setUsername(user);
                conn.setPassword(pwd);
                conn.setProxyType(Connection.ProxyType.NONE);
                conn.setUserIdStr("bridge:" + user);
                conn.setUserData(UserData.getDefaultUserDataView());
                boolean ok = session.connectStart(conn);
                if (ok) {
                    event("connected");
                    doGetTables();
                } else {
                    error("connect", session.getLastError());
                }
            } catch (Throwable t) {
                error("connect", String.valueOf(t.getMessage()));
            }
        });
    }

    private void doGetTables() {
        try {
            UUID room = session.getMainRoomId();
            if (room == null) {
                return;
            }
            Collection<TableView> tables = session.getTables(room);
            JsonObject o = new JsonObject();
            o.addProperty("type", "tables");
            String json = Json.tryToJson(tables);
            if (json != null) {
                o.add("data", com.google.gson.JsonParser.parseString(json));
            } else {
                o.addProperty("count", tables == null ? 0 : tables.size());
                o.addProperty("dataError", true);
            }
            send(o);
        } catch (Throwable t) {
            error("getTables", String.valueOf(t.getMessage()));
        }
    }

    public void doDisconnect() {
        try {
            session.connectStop(false, false);
        } catch (Throwable ignored) {
        }
        worker.shutdownNow();
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    private static UUID uuid(JsonObject o, String key) {
        return UUID.fromString(o.get(key).getAsString());
    }
}
