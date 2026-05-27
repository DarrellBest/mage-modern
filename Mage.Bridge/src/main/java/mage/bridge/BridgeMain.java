package mage.bridge;

/**
 * Entry point for the headless bridge process. Runs on bundled Java 8 (like the
 * Swing client) so JBoss remoting needs no add-opens. The Electron app launches
 * this and talks to it over the local WebSocket.
 *
 *   java -jar mage-bridge.jar [bindHost] [port]
 * defaults: 127.0.0.1 17190
 */
public final class BridgeMain {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 17190;

    public static void main(String[] args) {
        // ensure no GUI is ever needed
        System.setProperty("java.awt.headless", "true");

        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        BridgeServer server = new BridgeServer(host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1000);
            } catch (Exception ignored) {
            }
        }));
        server.run(); // blocks
    }

    private BridgeMain() {
    }
}
