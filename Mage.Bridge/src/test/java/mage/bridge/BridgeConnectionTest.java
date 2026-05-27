package mage.bridge;

import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.view.TableView;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Integration test: drives a REAL connection to the fork server using ONLY the
 * headless layer (no Swing). This is the Phase-0 proof that a headless Java
 * process can log in and read lobby state — the foundation the Electron UI rides on.
 *
 * Guarded: only runs with -Dbridge.it=true, so a normal {@code mvn install} skips it.
 *   mvn -pl Mage.Bridge test -Dtest=BridgeConnectionTest \
 *       -Dbridge.it=true -Dbridge.host=192.168.1.87 -Dbridge.port=17171
 */
public class BridgeConnectionTest {

    @Test
    public void connectsAndListsTablesHeadless() throws Exception {
        assumeTrue("integration test; pass -Dbridge.it=true to run", Boolean.getBoolean("bridge.it"));
        System.setProperty("java.awt.headless", "true");

        String host = System.getProperty("bridge.host", "192.168.1.87");
        int port = Integer.parseInt(System.getProperty("bridge.port", "17171"));
        String user = System.getProperty("bridge.user", "BridgeIT" + (System.currentTimeMillis() % 100000));

        HeadlessClient client = new HeadlessClient();
        List<String> callbacks = new CopyOnWriteArrayList<>();
        client.setCallbackSink(cb -> callbacks.add(cb.getMethod().name()));

        SessionImpl session = new SessionImpl(client);
        Connection c = new Connection();
        c.setHost(host);
        c.setPort(port);
        c.setUsername(user);
        c.setPassword("");
        c.setProxyType(Connection.ProxyType.NONE);
        c.setUserIdStr("bridge:it:" + user);
        c.setUserData(UserData.getDefaultUserDataView());

        System.out.println("[bridge IT] connecting to " + host + ":" + port + " as " + user);
        boolean ok;
        try {
            ok = session.connectStart(c);
        } catch (Throwable t) {
            System.out.println("[bridge IT] connectStart THREW: " + t);
            t.printStackTrace(System.out);
            throw t;
        }
        System.out.println("[bridge IT] connectStart=" + ok + " ; lastError=[" + session.getLastError() + "]");
        try {
            assertTrue("connectStart should succeed (lastError=" + session.getLastError() + ")", ok);
            assertTrue("session should report connected", session.isConnected());

            UUID room = session.getMainRoomId();
            assertNotNull("main room id", room);

            Collection<TableView> tables = session.getTables(room);
            assertNotNull("tables collection (may be empty)", tables);
            System.out.println("[bridge IT] OK — mainRoom=" + room + "; tables=" + tables.size()
                    + "; callbacks so far=" + callbacks);
        } finally {
            session.connectStop(false, false);
        }
    }
}
