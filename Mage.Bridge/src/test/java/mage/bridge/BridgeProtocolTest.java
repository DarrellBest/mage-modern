package mage.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end protocol test for the bridge: starts the WebSocket bridge, connects
 * a WebSocket client (standing in for Electron), sends a "connect" command, and
 * verifies the bridge logs into the live fork server and returns the tables list
 * — the exact path the Electron lobby will use.
 *
 * Guarded by -Dbridge.it=true (needs the live server).
 */
public class BridgeProtocolTest {

    @Test
    public void electronToBridgeToServerRoundTrip() throws Exception {
        assumeTrue("integration test; pass -Dbridge.it=true to run", Boolean.getBoolean("bridge.it"));
        System.setProperty("java.awt.headless", "true");

        int port = Integer.parseInt(System.getProperty("bridge.wsport", "17191"));
        String serverHost = System.getProperty("bridge.host", "192.168.1.87");
        int serverPort = Integer.parseInt(System.getProperty("bridge.port", "17171"));

        BridgeServer server = new BridgeServer("127.0.0.1", port);
        server.start();
        Thread.sleep(500); // let it bind

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch hello = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch tables = new CountDownLatch(1);

        WebSocketClient ws = new WebSocketClient(URI.create("ws://127.0.0.1:" + port)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) {
                received.add(m);
                String t = JsonParser.parseString(m).getAsJsonObject().get("type").getAsString();
                if ("hello".equals(t)) hello.countDown();
                if ("connected".equals(t)) connected.countDown();
                if ("tables".equals(t)) tables.countDown();
            }
            @Override public void onClose(int c, String r, boolean remote) { }
            @Override public void onError(Exception e) { }
        };

        try {
            assertTrue("ws client should connect to bridge", ws.connectBlocking(5, SECONDS));
            assertTrue("bridge should greet with hello", hello.await(5, SECONDS));

            JsonObject cmd = new JsonObject();
            cmd.addProperty("cmd", "connect");
            cmd.addProperty("host", serverHost);
            cmd.addProperty("port", serverPort);
            cmd.addProperty("user", "BridgeWS" + (System.currentTimeMillis() % 100000));
            cmd.addProperty("password", "");
            ws.send(cmd.toString());

            assertTrue("bridge should report connected (got: " + received + ")", connected.await(20, SECONDS));
            assertTrue("bridge should return tables (got: " + received + ")", tables.await(10, SECONDS));
            System.out.println("[bridge protocol IT] OK — messages: " + received.size());
        } finally {
            try { ws.closeBlocking(); } catch (Exception ignored) { }
            server.stop(1000);
        }
    }
}
