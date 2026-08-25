import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.SeatView;
import mage.view.TableView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin CLI for a running XMage server: list active tables/games, and kill exactly one of them
 * without restarting the server.
 *
 * Call path (identical to what the stock Mage.Server.Console admin GUI does):
 *   SessionImpl.connectStart(connection with adminPassword + username "Admin")
 *       -> MageServer.connectAdmin(password, sessionId, version)     [server: marks session isAdmin]
 *   SessionImpl.getMainRoomId()   -> MageServer.serverGetMainRoomId()
 *   SessionImpl.getTables(roomId) -> MageServer.roomGetAllTables(roomId)  -> List&lt;TableView&gt;
 *   SessionImpl.removeTable(tableId) -> MageServer.adminTableRemove(sessionId, tableId)
 *       -> TableManagerImpl.removeTable(adminUserId, tableId)
 *          (allowed because UserManagerImpl.isAdmin(userId) is true for the "Admin" user)
 *          -> TableController.leaveTableAll() + chat destroy
 *          -> TableManagerImpl.removeTable(tableId)
 *             -> TableController.cleanUp(), game.end(), GameManager.removeGame(gameId)
 *                (GameController.cleanUp: cancels timers, closes game sessions, destroys chat)
 *             -> GamesRoomManager.removeTable(tableId)
 *
 * The admin password is NEVER stored in this repo. It is read at runtime from either
 * $XMAGE_ADMIN_PASSWORD or the live server's own start script (default
 * /home/user/Documents/xmage/xmage/mage-server/start-fork.sh), from its -adminPassword= argument.
 *
 * Note: mage.utils.SystemUtil.sanitize() strips every non-alphanumeric character from the
 * -adminPassword= value on the SERVER side before storing it, so this tool applies the same
 * transformation before sending it. Without that, a password containing punctuation would be
 * rejected with "Wrong password" even though it matches the start script verbatim.
 */
public final class KillGame {

    private static final String DEFAULT_SERVER_DIR = "/home/user/Documents/xmage/xmage/mage-server";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 17171;

    /** seconds to wait for the server to actually drop the table from the lobby list */
    private static final int VERIFY_TIMEOUT_SECS = 20;
    /** GamesRoomImpl refreshes its cached lobby table list on a 2s fixed-rate schedule */
    private static final long VERIFY_POLL_MILLIS = 500L;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private enum Mode { LIST, KILL }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        Mode mode = null;
        String target = null;
        String host = null;
        String portText = null;
        String serverDir = envOr("XMAGE_SERVER_DIR", DEFAULT_SERVER_DIR);
        String startScript = System.getenv("XMAGE_START_SCRIPT");
        boolean assumeYes = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--list".equals(arg)) {
                mode = Mode.LIST;
            } else if ("--kill".equals(arg)) {
                mode = Mode.KILL;
                if (i + 1 >= args.length) {
                    return fail("--kill needs a game id or table id");
                }
                target = args[++i];
            } else if ("--host".equals(arg)) {
                if (i + 1 >= args.length) {
                    return fail("--host needs a value");
                }
                host = args[++i];
            } else if ("--port".equals(arg)) {
                if (i + 1 >= args.length) {
                    return fail("--port needs a value");
                }
                portText = args[++i];
            } else if ("--server-dir".equals(arg)) {
                if (i + 1 >= args.length) {
                    return fail("--server-dir needs a value");
                }
                serverDir = args[++i];
            } else if ("--start-script".equals(arg)) {
                if (i + 1 >= args.length) {
                    return fail("--start-script needs a value");
                }
                startScript = args[++i];
            } else if ("--yes".equals(arg) || "-y".equals(arg)) {
                assumeYes = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                usage(System.out);
                return 0;
            } else {
                return fail("unknown argument: " + arg);
            }
        }

        if (mode == null) {
            usage(System.err);
            return 2;
        }

        if (host == null) {
            host = envOr("XMAGE_HOST", null);
        }
        if (portText == null) {
            portText = envOr("XMAGE_PORT", null);
        }

        // fall back to whatever the live server itself is configured with
        String[] fromConfig = readHostPortFromConfig(Paths.get(serverDir, "config", "config.xml"));
        if (host == null) {
            host = fromConfig[0] != null ? fromConfig[0] : DEFAULT_HOST;
        }
        int port = DEFAULT_PORT;
        try {
            if (portText != null) {
                port = Integer.parseInt(portText.trim());
            } else if (fromConfig[1] != null) {
                port = Integer.parseInt(fromConfig[1].trim());
            }
        } catch (NumberFormatException ex) {
            return fail("bad port value: " + (portText != null ? portText : fromConfig[1]));
        }

        String adminPassword;
        try {
            adminPassword = resolveAdminPassword(serverDir, startScript);
        } catch (IOException ex) {
            return fail(ex.getMessage());
        }

        SilentClient client = new SilentClient();
        SessionImpl session = new SessionImpl(client);

        Connection connection = new Connection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(SessionImpl.ADMIN_NAME);
        connection.setAdminPassword(adminPassword);
        // must not be null: SessionImpl switches on it while opening the socket
        connection.setProxyType(Connection.ProxyType.NONE);

        note("connecting to " + host + ':' + port + " as " + SessionImpl.ADMIN_NAME);
        if (!session.connectStart(connection)) {
            String lastError = session.getLastError();
            return fail("admin connect failed"
                    + (lastError != null && !lastError.isEmpty() ? ": " + lastError : "")
                    + (client.lastMessage != null ? " (" + client.lastMessage + ')' : ""));
        }

        try {
            UUID roomId = session.getMainRoomId();
            if (roomId == null) {
                return fail("could not get main room id");
            }

            List<TableView> tables = fetchTables(session, roomId);
            if (mode == Mode.LIST) {
                printTables(tables);
                return 0;
            }

            List<TableView> matches = findMatches(tables, target);
            if (matches.isEmpty()) {
                System.err.println("No table matches '" + target + "'. Current tables:");
                printTables(tables);
                return 3;
            }
            if (matches.size() > 1) {
                System.err.println("'" + target + "' is ambiguous - it matches " + matches.size()
                        + " tables. Re-run with a full id.");
                printTables(matches);
                return 3;
            }

            TableView victim = matches.get(0);
            System.out.println("About to remove exactly this table:");
            printTable(victim);

            if (!assumeYes) {
                if (System.console() == null) {
                    return fail("refusing to kill a table non-interactively without --yes");
                }
                String answer = System.console().readLine("Type the table id to confirm: ");
                if (answer == null || !answer.trim().equals(victim.getTableId().toString())) {
                    return fail("confirmation did not match - nothing was removed");
                }
            }

            UUID tableId = victim.getTableId();
            note("calling adminTableRemove(sessionId, " + tableId + ')');
            if (!session.removeTable(tableId)) {
                return fail("removeTable call failed (is the session still connected?)");
            }

            // adminTableRemove runs asynchronously in the server's call executor, and the room's
            // table list is a cache refreshed every 2s - so poll instead of trusting the return.
            long deadline = System.currentTimeMillis() + (VERIFY_TIMEOUT_SECS * 1000L);
            boolean gone = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(VERIFY_POLL_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!containsTable(fetchTables(session, roomId), tableId)) {
                    gone = true;
                    break;
                }
            }

            if (!gone) {
                System.err.println("Table " + tableId + " is STILL listed after "
                        + VERIFY_TIMEOUT_SECS + "s. It was not removed.");
                System.err.println("Check the server log for 'Wrong admin access' - that means the "
                        + "admin password did not take.");
                return 4;
            }

            System.out.println("Removed table " + tableId + " (" + nullSafe(victim.getTableName()) + ")");
            System.out.println("Remaining tables:");
            printTables(fetchTables(session, roomId));
            return 0;
        } catch (Exception ex) {
            return fail("error talking to server: " + ex);
        } finally {
            try {
                session.connectStop(false, false);
            } catch (Exception ignore) {
                // best effort
            }
        }
    }

    // ---------------------------------------------------------------- tables

    private static List<TableView> fetchTables(SessionImpl session, UUID roomId) throws Exception {
        Collection<TableView> tables = session.getTables(roomId);
        return tables == null ? new ArrayList<TableView>() : new ArrayList<TableView>(tables);
    }

    private static boolean containsTable(List<TableView> tables, UUID tableId) {
        for (TableView table : tables) {
            if (tableId.equals(table.getTableId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A target may be a table id or a game id, given in full or as a unique prefix.
     */
    private static List<TableView> findMatches(List<TableView> tables, String target) {
        String needle = target.trim().toLowerCase();
        List<TableView> found = new ArrayList<TableView>();
        for (TableView table : tables) {
            boolean hit = table.getTableId() != null
                    && table.getTableId().toString().toLowerCase().startsWith(needle);
            if (!hit && table.getGames() != null) {
                for (UUID gameId : table.getGames()) {
                    if (gameId != null && gameId.toString().toLowerCase().startsWith(needle)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                found.add(table);
            }
        }
        return found;
    }

    private static void printTables(List<TableView> tables) {
        if (tables.isEmpty()) {
            System.out.println("(no active tables)");
            return;
        }
        System.out.println(tables.size() + " active table(s):");
        for (TableView table : tables) {
            printTable(table);
        }
    }

    private static void printTable(TableView table) {
        StringBuilder sb = new StringBuilder();
        sb.append("  TABLE ").append(table.getTableId())
                .append("  state=").append(table.getTableState())
                .append("  kind=").append(table.isTournament() ? "tournament" : "match")
                .append("  name=").append(nullSafe(table.getTableName()))
                .append("  type=").append(nullSafe(table.getGameType()));
        if (table.getCreateTime() != null) {
            sb.append("  started=").append(STAMP.format(table.getCreateTime()));
        }
        System.out.println(sb.toString());
        System.out.println("        controller: " + nullSafe(table.getControllerName()));

        StringBuilder seats = new StringBuilder();
        if (table.getSeats() != null) {
            for (SeatView seat : table.getSeats()) {
                if (seats.length() > 0) {
                    seats.append(", ");
                }
                seats.append(nullSafe(seat.getPlayerName()))
                        .append(" (").append(seat.getPlayerType()).append(')');
            }
        }
        System.out.println("        players:    " + (seats.length() > 0 ? seats.toString() : "(none)"));

        StringBuilder games = new StringBuilder();
        if (table.getGames() != null) {
            for (UUID gameId : table.getGames()) {
                if (games.length() > 0) {
                    games.append(", ");
                }
                games.append(gameId);
            }
        }
        System.out.println("        games:      " + (games.length() > 0 ? games.toString() : "(none)"));
    }

    // ------------------------------------------------------------ config i/o

    /** @return {host, port}, either element may be null */
    private static String[] readHostPortFromConfig(Path configXml) {
        String[] result = new String[]{null, null};
        if (!Files.isReadable(configXml)) {
            return result;
        }
        try {
            String xml = new String(Files.readAllBytes(configXml), StandardCharsets.UTF_8);
            Matcher host = Pattern.compile("serverAddress\\s*=\\s*\"([^\"]*)\"").matcher(xml);
            if (host.find()) {
                result[0] = host.group(1);
            }
            Matcher port = Pattern.compile("[^a-zA-Z]port\\s*=\\s*\"(\\d+)\"").matcher(xml);
            if (port.find()) {
                result[1] = port.group(1);
            }
        } catch (IOException ignore) {
            // fall back to defaults
        }
        return result;
    }

    /**
     * Never hardcode the admin password in this repo. Order of preference:
     *   1. $XMAGE_ADMIN_PASSWORD
     *   2. the -adminPassword= argument inside the live server's start script
     */
    private static String resolveAdminPassword(String serverDir, String startScript) throws IOException {
        String fromEnv = System.getenv("XMAGE_ADMIN_PASSWORD");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            note("admin password: from $XMAGE_ADMIN_PASSWORD");
            return sanitize(fromEnv.trim());
        }

        Path script = startScript != null
                ? Paths.get(startScript)
                : Paths.get(serverDir, "start-fork.sh");
        if (!Files.isReadable(script)) {
            throw new IOException("cannot read admin password: no $XMAGE_ADMIN_PASSWORD and "
                    + script + " is not readable");
        }

        String text = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("-adminPassword=(?:\"([^\"]*)\"|'([^']*)'|(\\S+))").matcher(text);
        if (!m.find()) {
            throw new IOException("no -adminPassword= argument found in " + script);
        }
        String raw = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
        String clean = sanitize(raw);
        if (clean.isEmpty()) {
            throw new IOException("-adminPassword= in " + script + " is empty after sanitizing");
        }
        note("admin password: read from " + script);
        return clean;
    }

    /**
     * Mirror of mage.utils.SystemUtil.sanitize(), which the server applies to -adminPassword=
     * before storing it. connectAdmin compares against the sanitized value, so the client has
     * to send the sanitized form too.
     */
    private static String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9]", "");
    }

    // ---------------------------------------------------------------- helpers

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void note(String message) {
        System.err.println("[kill-game] " + message);
    }

    private static int fail(String message) {
        System.err.println("[kill-game] ERROR: " + message);
        return 1;
    }

    private static void usage(java.io.PrintStream out) {
        out.println("Usage:");
        out.println("  kill-game.sh --list");
        out.println("  kill-game.sh --kill <gameId-or-tableId> [--yes]");
        out.println();
        out.println("Options:");
        out.println("  --list                    show every active table with its games and players");
        out.println("  --kill <id>               remove exactly one table, by table id or game id");
        out.println("                            (a unique id prefix is accepted)");
        out.println("  --yes, -y                 skip the interactive confirmation (required when");
        out.println("                            stdin is not a terminal)");
        out.println("  --host <h>  --port <p>    override the server address");
        out.println("  --server-dir <dir>        live server dir (default " + DEFAULT_SERVER_DIR + ')');
        out.println("  --start-script <file>     script to read -adminPassword= from");
        out.println();
        out.println("Environment:");
        out.println("  XMAGE_ADMIN_PASSWORD      admin password (else read from the start script)");
        out.println("  XMAGE_SERVER_DIR, XMAGE_START_SCRIPT, XMAGE_HOST, XMAGE_PORT");
    }

    /**
     * Minimal MageClient. The server pushes chat/game callbacks down the same connection;
     * an admin session gets very few, and we deliberately ignore all of them.
     */
    private static final class SilentClient implements MageClient {

        private final MageVersion version = new MageVersion(KillGame.class);
        private volatile String lastMessage;

        @Override
        public MageVersion getVersion() {
            return version;
        }

        @Override
        public void connected(String message) {
            note("connected: " + message);
        }

        @Override
        public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
            // nothing to do, the CLI is exiting anyway
        }

        @Override
        public void showMessage(String message) {
            lastMessage = message;
            note("server says: " + message);
        }

        @Override
        public void showError(String message) {
            lastMessage = message;
            note("server error: " + message);
        }

        @Override
        public void onNewConnection() {
            // nothing to do
        }

        @Override
        public void onCallback(ClientCallback callback) {
            // admin session ignores all pushed callbacks
        }
    }
}
