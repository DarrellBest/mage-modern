package mage.bridge;

import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure unit tests for the headless client (no network). */
public class HeadlessClientTest {

    @Test
    public void reportsAVersion() {
        // must report a non-null version so the server's version check has something to compare
        assertNotNull(new HeadlessClient().getVersion());
    }

    @Test
    public void forwardsCallbacksToSink() {
        HeadlessClient client = new HeadlessClient();
        AtomicReference<ClientCallback> seen = new AtomicReference<>();
        client.setCallbackSink(seen::set);

        ClientCallback cb = new ClientCallback(ClientCallbackMethod.CHATMESSAGE, null, "hello");
        client.onCallback(cb);

        assertNotNull(seen.get());
        assertEquals(ClientCallbackMethod.CHATMESSAGE, seen.get().getMethod());
    }

    @Test
    public void tracksConnectionState() {
        HeadlessClient client = new HeadlessClient();
        assertFalse(client.isConnected());
        client.connected("server");
        assertTrue(client.isConnected());
        client.disconnected(false, false);
        assertFalse(client.isConnected());
    }
}
