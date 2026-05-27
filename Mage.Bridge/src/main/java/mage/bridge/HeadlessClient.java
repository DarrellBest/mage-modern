package mage.bridge;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.utils.MageVersion;

import java.util.function.Consumer;

/**
 * Headless replacement for the Swing {@code MageFrame}/{@code CallbackClientImpl}.
 * It implements the same {@link MageClient} contract the network layer expects,
 * but instead of driving Swing it forwards every server-pushed {@link ClientCallback}
 * to a sink (the WebSocket layer serializes it to JSON for the Electron UI).
 *
 * Reports the same {@link MageVersion} as the real client (built from the same code),
 * so the server's version check passes.
 */
public class HeadlessClient implements MageClient {

    private final MageVersion version = new MageVersion(HeadlessClient.class);

    private volatile Consumer<ClientCallback> callbackSink = c -> { };
    private volatile Consumer<ConnectionEvent> connectionSink = e -> { };
    private volatile boolean connected = false;

    /** A connection-state / message event (connected, disconnected, info, error). */
    public static final class ConnectionEvent {
        public enum Kind { CONNECTED, DISCONNECTED, MESSAGE, ERROR, NEW_CONNECTION }
        public final Kind kind;
        public final String message;
        public ConnectionEvent(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }
    }

    public void setCallbackSink(Consumer<ClientCallback> sink) {
        this.callbackSink = sink != null ? sink : c -> { };
    }

    public void setConnectionSink(Consumer<ConnectionEvent> sink) {
        this.connectionSink = sink != null ? sink : e -> { };
    }

    public boolean isConnected() {
        return connected;
    }

    @Override
    public MageVersion getVersion() {
        return version;
    }

    @Override
    public void connected(String message) {
        connected = true;
        connectionSink.accept(new ConnectionEvent(ConnectionEvent.Kind.CONNECTED, message));
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        connected = false;
        connectionSink.accept(new ConnectionEvent(ConnectionEvent.Kind.DISCONNECTED, "askReconnect=" + askToReconnect));
    }

    @Override
    public void showMessage(String message) {
        connectionSink.accept(new ConnectionEvent(ConnectionEvent.Kind.MESSAGE, message));
    }

    @Override
    public void showError(String message) {
        connectionSink.accept(new ConnectionEvent(ConnectionEvent.Kind.ERROR, message));
    }

    @Override
    public void onNewConnection() {
        connectionSink.accept(new ConnectionEvent(ConnectionEvent.Kind.NEW_CONNECTION, null));
    }

    @Override
    public void onCallback(ClientCallback callback) {
        callbackSink.accept(callback);
    }
}
