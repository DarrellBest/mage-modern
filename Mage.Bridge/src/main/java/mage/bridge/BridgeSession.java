package mage.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.constants.MatchBufferTime;
import mage.constants.MatchTimeLimit;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PlayerAction;
import mage.constants.RangeOfInfluence;
import mage.constants.SkillLevel;
import mage.game.match.MatchOptions;
import mage.players.PlayerType;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.players.net.UserData;
import mage.remote.Connection;
import mage.remote.SessionImpl;
import mage.view.TableClientMessage;
import mage.view.TableView;
import org.java_websocket.WebSocket;

import java.io.File;
import java.io.FileWriter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One bridge session = one Electron connection. Owns a real {@link SessionImpl}
 * (the XMage network/session layer) + a {@link HeadlessClient}. Translates JSON
 * commands from Electron into Session calls and forwards server-pushed callbacks
 * back to Electron as JSON. No game logic lives here — pure protocol translation.
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
    private volatile UUID mainRoomId;
    private volatile UUID mainChatId;

    public BridgeSession(WebSocket socket) {
        this.socket = socket;
        client.setCallbackSink(this::forwardCallback);
        client.setConnectionSink(this::forwardConnectionEvent);
    }

    // ---------- outbound (bridge -> Electron) ----------

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

    private void reply(String id, String cmd, boolean ok, Object data, String error) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "reply");
        if (id != null) o.addProperty("id", id);
        o.addProperty("cmd", cmd);
        o.addProperty("ok", ok);
        if (error != null) o.addProperty("error", error);
        if (data != null) {
            String json = Json.tryToJson(data);
            if (json != null) {
                o.add("data", JsonParser.parseString(json));
            } else {
                o.addProperty("dataError", true);
            }
        }
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

    private void forwardCallback(ClientCallback cb) {
        try {
            cb.decompressData(); // callback payloads arrive gzip-compressed; must decompress before getData()
            // Act on lifecycle callbacks the UI can't (subscribe to a started game so updates flow).
            if (cb.getMethod() == ClientCallbackMethod.START_GAME && cb.getData() instanceof TableClientMessage) {
                UUID gameId = ((TableClientMessage) cb.getData()).getGameId();
                if (gameId != null) {
                    session.joinGame(gameId);
                    session.getGameChatId(gameId).ifPresent(session::joinChat);
                }
            }

            JsonObject o = new JsonObject();
            o.addProperty("type", "callback");
            o.addProperty("method", cb.getMethod().name());
            o.addProperty("messageId", cb.getMessageId());
            if (cb.getObjectId() != null) {
                o.addProperty("objectId", cb.getObjectId().toString());
            }
            Object payload = cb.getData();
            String data = Json.tryToJson(payload);
            if (data != null) {
                o.add("data", JsonParser.parseString(data));
            } else {
                o.addProperty("dataError", true);
            }
            send(o);
        } catch (Throwable t) {
            System.err.println("[bridge] forwardCallback FAILED for " + cb.getMethod() + ": " + t);
            t.printStackTrace();
        }
    }

    // ---------- inbound (Electron -> bridge) ----------

    /** Dispatch one JSON command. Never throws. */
    public void handle(JsonObject cmd) {
        final String c = cmd.has("cmd") ? cmd.get("cmd").getAsString() : "";
        final String id = cmd.has("id") ? cmd.get("id").getAsString() : null;
        try {
            switch (c) {
                case "connect":            doConnect(cmd); break;
                case "disconnect":         doDisconnect(); reply(id, c, true, null, null); break;

                // lobby reads
                case "getTables":          reply(id, c, true, session.getTables(mainRoomId), null); break;
                case "getGameTypes":       reply(id, c, true, session.getGameTypes(), null); break;
                case "getDeckTypes":       reply(id, c, true, session.getDeckTypes(), null); break;
                case "getRoomUsers":       reply(id, c, true, session.getRoomUsers(mainRoomId), null); break;
                case "getServerMessages":  reply(id, c, true, session.getServerMessages(), null); break;

                // chat
                case "chat":               doChat(cmd, id); break;
                case "joinChat":           reply(id, c, session.joinChat(uuid(cmd, "chatId")), null, null); break;
                case "leaveChat":          reply(id, c, session.leaveChat(uuid(cmd, "chatId")), null, null); break;

                // deck building
                case "searchCards":        doSearchCards(cmd, id); break;
                case "listDecks":          reply(id, c, true, listDeckNames(), null); break;
                case "loadDeck":           reply(id, c, true, DeckImporter.importDeckFromFile(deckFile(cmd.get("name").getAsString()).getAbsolutePath(), false), null); break;
                case "saveDeck":           doSaveDeck(cmd, id); break;
                case "deleteDeck":         deckFile(cmd.get("name").getAsString()).delete(); reply(id, c, true, null, null); break;

                // tables / matches
                case "quickGame":          doQuickGame(cmd, id); break;
                case "createGame":         doCreateGame(cmd, id); break;
                case "joinTable":          doJoinTable(cmd, id); break;
                case "leaveTable":         reply(id, c, session.leaveTable(mainRoomId, uuid(cmd, "tableId")), null, null); break;
                case "removeTable":        reply(id, c, session.removeTable(mainRoomId, uuid(cmd, "tableId")), null, null); break;
                case "startMatch":         reply(id, c, session.startMatch(mainRoomId, uuid(cmd, "tableId")), null, null); break;
                case "watchTable":         reply(id, c, session.watchTable(mainRoomId, uuid(cmd, "tableId")), null, null); break;
                case "watchGame":          reply(id, c, session.watchGame(uuid(cmd, "gameId")), null, null); break;
                case "joinGame":           reply(id, c, session.joinGame(uuid(cmd, "gameId")), null, null); break;

                // in-game player responses
                case "sendPlayerBoolean":  reply(id, c, session.sendPlayerBoolean(uuid(cmd, "gameId"), cmd.get("value").getAsBoolean()), null, null); break;
                case "sendPlayerInteger":  reply(id, c, session.sendPlayerInteger(uuid(cmd, "gameId"), cmd.get("value").getAsInt()), null, null); break;
                case "sendPlayerString":   reply(id, c, session.sendPlayerString(uuid(cmd, "gameId"), cmd.get("value").getAsString()), null, null); break;
                case "sendPlayerUUID":     reply(id, c, session.sendPlayerUUID(uuid(cmd, "gameId"), uuid(cmd, "value")), null, null); break;
                case "sendPlayerAction":   doPlayerAction(cmd, id); break;
                case "concede":            reply(id, c, session.sendPlayerAction(PlayerAction.CLIENT_CONCEDE_GAME, uuid(cmd, "gameId"), null), null, null); break;
                case "quitMatch":          reply(id, c, session.quitMatch(uuid(cmd, "gameId")), null, null); break;

                default:                   error(c, "unknown command");
            }
        } catch (Throwable t) {
            if (id != null) reply(id, c, false, null, String.valueOf(t.getMessage()));
            else error(c, String.valueOf(t.getMessage()));
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
                if (session.connectStart(conn)) {
                    mainRoomId = session.getMainRoomId();
                    Optional<UUID> chat = session.getRoomChatId(mainRoomId);
                    chat.ifPresent(cid -> { mainChatId = cid; session.joinChat(cid); });
                    event("connected");
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "tables");
                    String json = Json.tryToJson(session.getTables(mainRoomId));
                    if (json != null) o.add("data", JsonParser.parseString(json));
                    send(o);
                } else {
                    error("connect", session.getLastError());
                }
            } catch (Throwable t) {
                error("connect", String.valueOf(t.getMessage()));
            }
        });
    }

    private void doChat(JsonObject cmd, String id) {
        UUID chatId = cmd.has("chatId") ? uuid(cmd, "chatId") : mainChatId;
        if (chatId == null) { reply(id, "chat", false, null, "no chat joined"); return; }
        reply(id, "chat", session.sendChatMessage(chatId, cmd.get("text").getAsString()), null, null);
    }

    private void doPlayerAction(JsonObject cmd, String id) {
        PlayerAction action = PlayerAction.valueOf(cmd.get("action").getAsString());
        UUID gameId = uuid(cmd, "gameId");
        reply(id, "sendPlayerAction", session.sendPlayerAction(action, gameId, null), null, null);
    }

    private void doJoinTable(JsonObject cmd, String id) throws Exception {
        UUID tableId = uuid(cmd, "tableId");
        String name = cmd.has("name") ? cmd.get("name").getAsString() : session.getUserName();
        DeckCardLists deck = basicLandTestDeck();
        boolean ok = session.joinTable(mainRoomId, tableId, name, PlayerType.HUMAN, 1, deck, "");
        reply(id, "joinTable", ok, null, null);
    }

    /** Replicates the Swing client's createTestGame: a quick match vs AI with a basic-land deck. */
    private void doQuickGame(JsonObject cmd, String id) {
        worker.submit(() -> {
            try {
                String gameType = cmd.has("gameType") ? cmd.get("gameType").getAsString() : "Momir Basic Two Player Duel";
                String deckType = cmd.has("deckType") ? cmd.get("deckType").getAsString() : "Variant Magic - Momir Basic";
                DeckCardLists deck = basicLandTestDeck();

                MatchOptions options = new MatchOptions("Vs AI", gameType, false);
                options.getPlayerTypes().add(PlayerType.HUMAN);
                options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
                options.setDeckType(deckType);
                options.setAttackOption(MultiplayerAttackOption.MULTIPLE);
                options.setRange(RangeOfInfluence.ONE);
                options.setWinsNeeded(1);
                options.setMatchTimeLimit(MatchTimeLimit.NONE);
                options.setMatchBufferTime(MatchBufferTime.NONE);
                options.setFreeMulligans(2);
                options.setSkillLevel(SkillLevel.CASUAL);
                options.setRollbackTurnsAllowed(true);
                options.setQuitRatio(100);
                options.setMinimumRating(0);

                TableView table = session.createTable(mainRoomId, options);
                if (table == null) { reply(id, "quickGame", false, null, "createTable null (gameType '" + gameType + "'): " + session.getLastError()); return; }
                UUID tableId = table.getTableId();
                boolean j1 = session.joinTable(mainRoomId, tableId, session.getUserName(), PlayerType.HUMAN, 1, deck, "");
                String e1 = session.getLastError();
                boolean j2 = session.joinTable(mainRoomId, tableId, "Computer", PlayerType.COMPUTER_MAD, 1, deck, "");
                String e2 = session.getLastError();
                boolean started = session.startMatch(mainRoomId, tableId);
                String e3 = session.getLastError();
                if (!started) {
                    reply(id, "quickGame", false, null,
                        "join1=" + j1 + "(" + e1 + ") join2=" + j2 + "(" + e2 + ") startMatch=false(" + e3 + ")");
                    return;
                }
                reply(id, "quickGame", true, "{\"tableId\":\"" + tableId + "\"}", null);
            } catch (Throwable t) {
                reply(id, "quickGame", false, null, String.valueOf(t.getMessage()));
            }
        });
    }

    // ---------- deck building ----------

    private static File decksDir() {
        File dir = new File(System.getProperty("user.home"), "XMage/decks");
        dir.mkdirs();
        return dir;
    }

    private static File deckFile(String name) {
        return new File(decksDir(), name.replaceAll("[^A-Za-z0-9 _-]", "_") + ".dck");
    }

    private java.util.List<String> listDeckNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        File[] files = decksDir().listFiles((d, n) -> n.toLowerCase().endsWith(".dck"));
        if (files != null) {
            for (File f : files) {
                names.add(f.getName().replaceFirst("\\.dck$", ""));
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private void doSearchCards(JsonObject cmd, String id) {
        String q = cmd.has("query") ? cmd.get("query").getAsString().trim() : "";
        int limit = cmd.has("limit") ? cmd.get("limit").getAsInt() : 60;
        JsonArray arr = new JsonArray();
        if (q.length() >= 2) {
            CardCriteria cc = new CardCriteria().nameContains(q);
            int n = 0;
            for (CardInfo ci : CardRepository.instance.findCards(cc)) {
                if (n++ >= limit) break;
                JsonObject o = new JsonObject();
                o.addProperty("name", ci.getName());
                o.addProperty("setCode", ci.getSetCode());
                o.addProperty("cardNumber", ci.getCardNumber());
                try { o.addProperty("manaCost", String.join("", ci.getManaCosts(CardInfo.ManaCostSide.ALL))); } catch (Throwable ignored) { }
                try { o.addProperty("types", String.valueOf(ci.getTypes())); } catch (Throwable ignored) { }
                try { o.addProperty("rarity", ci.getRarity() != null ? ci.getRarity().toString() : ""); } catch (Throwable ignored) { }
                o.addProperty("power", ci.getPower());
                o.addProperty("toughness", ci.getToughness());
                arr.add(o);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "reply");
        out.addProperty("id", id);
        out.addProperty("cmd", "searchCards");
        out.addProperty("ok", true);
        out.add("data", arr);
        send(out);
    }

    private void doSaveDeck(JsonObject cmd, String id) throws Exception {
        String name = cmd.get("name").getAsString();
        JsonObject deck = cmd.getAsJsonObject("deck");
        try (FileWriter w = new FileWriter(deckFile(name))) {
            writeCards(w, deck.has("cards") ? deck.getAsJsonArray("cards") : null, "");
            writeCards(w, deck.has("sideboard") ? deck.getAsJsonArray("sideboard") : null, "SB: ");
        }
        reply(id, "saveDeck", true, null, null);
    }

    private void writeCards(FileWriter w, JsonArray cards, String prefix) throws Exception {
        if (cards == null) return;
        for (JsonElement e : cards) {
            JsonObject c = e.getAsJsonObject();
            int amt = c.has("amount") ? c.get("amount").getAsInt() : 1;
            String set = c.has("setCode") && !c.get("setCode").isJsonNull() ? c.get("setCode").getAsString() : "";
            String num = c.has("cardNumber") && !c.get("cardNumber").isJsonNull() ? c.get("cardNumber").getAsString() : "";
            String nm = c.get("cardName").getAsString();
            String loc = (!set.isEmpty() && !num.isEmpty()) ? (" [" + set + ":" + num + "]") : "";
            w.write(prefix + amt + loc + " " + nm + "\n");
        }
    }

    /** Custom game: chosen game type + deck (saved deck by name, or the basic-land fallback) + N AI seats. */
    private void doCreateGame(JsonObject cmd, String id) {
        worker.submit(() -> {
            try {
                String gameType = cmd.has("gameType") ? cmd.get("gameType").getAsString() : "Momir Basic Two Player Duel";
                String deckType = cmd.has("deckType") ? cmd.get("deckType").getAsString() : "Variant Magic - Momir Basic";
                int ai = cmd.has("aiOpponents") ? Math.max(1, cmd.get("aiOpponents").getAsInt()) : 1;
                DeckCardLists deck = cmd.has("deckName")
                        ? DeckImporter.importDeckFromFile(deckFile(cmd.get("deckName").getAsString()).getAbsolutePath(), false)
                        : basicLandTestDeck();

                boolean multi = (1 + ai) > 2;
                MatchOptions options = new MatchOptions("Custom Game", gameType, multi);
                options.getPlayerTypes().add(PlayerType.HUMAN);
                for (int i = 0; i < ai; i++) options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
                options.setDeckType(deckType);
                options.setAttackOption(MultiplayerAttackOption.MULTIPLE);
                options.setRange(RangeOfInfluence.ONE);
                options.setWinsNeeded(1);
                options.setMatchTimeLimit(MatchTimeLimit.NONE);
                options.setMatchBufferTime(MatchBufferTime.NONE);
                options.setFreeMulligans(2);
                options.setSkillLevel(SkillLevel.CASUAL);
                options.setRollbackTurnsAllowed(true);
                options.setQuitRatio(100);
                options.setMinimumRating(0);

                TableView table = session.createTable(mainRoomId, options);
                if (table == null) { reply(id, "createGame", false, null, "createTable null (gameType '" + gameType + "'): " + session.getLastError()); return; }
                UUID tid = table.getTableId();
                boolean jh = session.joinTable(mainRoomId, tid, session.getUserName(), PlayerType.HUMAN, 1, deck, "");
                for (int i = 0; i < ai; i++) session.joinTable(mainRoomId, tid, "Computer " + (i + 1), PlayerType.COMPUTER_MAD, 1, deck, "");
                boolean started = session.startMatch(mainRoomId, tid);
                reply(id, "createGame", started, "{\"tableId\":\"" + tid + "\"}",
                        started ? null : ("join=" + jh + " start failed: " + session.getLastError()));
            } catch (Throwable t) {
                reply(id, "createGame", false, null, String.valueOf(t.getMessage()));
            }
        });
    }

    /** 25 basic lands — the same minimal deck the Swing client's test game uses. */
    private DeckCardLists basicLandTestDeck() throws Exception {
        File f = File.createTempFile("bridge-deck", ".dck");
        f.deleteOnExit();
        try (FileWriter w = new FileWriter(f)) {
            w.write("12 Swamp\n12 Forest\n12 Island\n12 Mountain\n12 Plains\n");
        }
        return DeckImporter.importDeckFromFile(f.getAbsolutePath(), false);
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
