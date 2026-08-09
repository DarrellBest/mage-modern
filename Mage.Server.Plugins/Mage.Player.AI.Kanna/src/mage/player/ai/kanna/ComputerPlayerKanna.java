package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer7;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proof-of-concept AI player: normal ComputerPlayer7 logic for everything,
 * except attack declaration, which is decided by an LLM (via Ollama tool
 * calling) instead of the built-in heuristics.
 * <p>
 * Extends ComputerPlayer7, not ComputerPlayer6 -- ComputerPlayer6 alone has no
 * priority() override and falls back to ComputerPlayer's "minimum
 * implementation for do nothing" (just passes every priority window).
 * ComputerPlayer7 is what actually wires the simulation/decision machinery up
 * to real play.
 * <p>
 * Isolated in its own plugin module on purpose -- never touches shared
 * engine files, so pulling from upstream never conflicts with this class.
 *
 * @author Darrell Best
 */
public class ComputerPlayerKanna extends ComputerPlayer7 {

    private static final Logger logger = Logger.getLogger(ComputerPlayerKanna.class);

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String OLLAMA_MODEL = "deepseek-v4-pro:cloud";
    private static final int REQUEST_TIMEOUT_MS = 30_000;

    // kept small on purpose: this gets re-sent in full with every prompt, so history length
    // is a direct, ongoing token cost, not a one-time one
    private static final int MAX_HISTORY_ENTRIES = 5;
    private final Deque<String> combatHistory = new ArrayDeque<>();

    public ComputerPlayerKanna(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public ComputerPlayerKanna(final ComputerPlayerKanna player) {
        super(player);
        this.combatHistory.addAll(player.combatHistory);
    }

    @Override
    public ComputerPlayerKanna copy() {
        return new ComputerPlayerKanna(this);
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        logger.info("Kanna: selectAttackers called for " + getName());

        // same protocol the stock declareAttackers() follows: fire the pre-combat event, then
        // respect any replacement effect that prevents/replaces declaring attackers entirely
        // (e.g. "you can't attack this turn"). Once this fires we're committed to handling this
        // combat step ourselves -- falling back to super.selectAttackers() after this point would
        // fire both events a second time and double-trigger any "before combat" abilities.
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, attackingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, attackingPlayerId, attackingPlayerId))) {
            logger.info("Kanna: declaring attackers was replaced/prevented this combat");
            return;
        }

        try {
            chooseAttackersWithKanna(game, attackingPlayerId);
        } catch (Throwable e) {
            // safe default on any failure: declare no attacks this combat, rather than risk
            // re-running (and double-firing) the normal AI's own declareAttackers logic
            logger.warn("Kanna: LLM attack decision failed, declaring no attacks this combat - " + e, e);
        }
    }

    private void chooseAttackersWithKanna(Game game, UUID attackingPlayerId) throws Exception {
        // dedup by real permanent id first -- a creature can be a legal attacker against
        // more than one opponent, and must only be listed/offered once either way
        Map<UUID, Permanent> uniqueAttackers = new HashMap<>();
        Map<String, UUID> defendersById = new HashMap<>();
        StringBuilder defendersDesc = new StringBuilder();

        for (UUID defenderId : game.getOpponents(playerId, true)) {
            Player defender = game.getPlayer(defenderId);
            if (defender == null || !defender.isInGame()) {
                continue;
            }
            String defId = "def-" + defendersById.size();
            defendersById.put(defId, defenderId);
            defendersDesc.append(String.format("- %s: %s (%d life)%n", defId, defender.getName(), defender.getLife()));

            for (Permanent attacker : getAvailableAttackers(defenderId, game)) {
                uniqueAttackers.putIfAbsent(attacker.getId(), attacker);
            }
        }

        if (uniqueAttackers.isEmpty() || defendersById.isEmpty()) {
            logger.info("Kanna: no legal attackers/defenders this combat, nothing to do");
            return;
        }

        // short synthetic ids instead of raw UUIDs -- much less error-prone for the LLM to echo back
        Map<String, Permanent> attackersById = new HashMap<>();
        StringBuilder attackersDesc = new StringBuilder();
        for (Permanent attacker : uniqueAttackers.values()) {
            String atkId = "atk-" + attackersById.size();
            attackersById.put(atkId, attacker);
            attackersDesc.append(String.format("- %s: %s (%d/%d)%n",
                    atkId, attacker.getName(), attacker.getPower().getValue(), attacker.getToughness().getValue()));
        }

        // compact on purpose -- one line per past turn, no rationale/thinking text -- since this
        // gets re-sent with every single prompt, not just paid for once
        String historyText = combatHistory.isEmpty()
                ? ""
                : "Your recent combat decisions:\n" + String.join("\n", combatHistory) + "\n\n";

        String prompt = String.format(
                "You are Kanna, playing Magic: The Gathering as %s. It's your combat step. Decide which of "
                        + "your available creatures should attack, and who each one attacks. It's fine to attack with none "
                        + "of them if that's the better play.%n%n%sYour available attackers:%n%s%nPossible defenders:%n%s%n"
                        + "Call declare_attackers using only the short ids listed above.",
                getName(), historyText, attackersDesc, defendersDesc
        );

        logger.info("Kanna: prompt sent to " + OLLAMA_MODEL + " for " + getName() + ":\n" + prompt);

        JsonObject toolCall = callOllamaForAttackDecision(prompt);
        if (toolCall == null) {
            logger.info("Kanna: model chose not to attack this combat");
            recordHistory(game, "declared no attacks");
            return;
        }
        logger.info("Kanna: raw tool-call arguments: " + toolCall);

        JsonArray attacks = toolCall.getAsJsonArray("attacks");
        if (attacks == null) {
            logger.warn("Kanna: tool-call response had no 'attacks' array, declaring no attacks");
            return;
        }

        Player attackingPlayer = game.getPlayer(attackingPlayerId);
        List<UUID> declared = new ArrayList<>();
        List<String> declaredSummary = new ArrayList<>();
        for (JsonElement el : attacks) {
            JsonObject pair = el.getAsJsonObject();
            String atkId = pair.has("attacker_id") ? pair.get("attacker_id").getAsString() : null;
            String defId = pair.has("defender_id") ? pair.get("defender_id").getAsString() : null;
            Permanent attacker = atkId == null ? null : attackersById.get(atkId);
            UUID defenderId = defId == null ? null : defendersById.get(defId);
            boolean legalAgainstThisDefender = attacker != null && defenderId != null
                    && getAvailableAttackers(defenderId, game).stream().anyMatch(p -> p.getId().equals(attacker.getId()));
            if (attacker == null || defenderId == null || declared.contains(attacker.getId()) || !legalAgainstThisDefender) {
                logger.warn("Kanna: ignoring invalid/hallucinated attack pair from LLM: " + atkId + " -> " + defId);
                continue;
            }
            attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
            declared.add(attacker.getId());
            Player defenderPlayer = game.getPlayer(defenderId);
            declaredSummary.add(attacker.getName() + " -> " + (defenderPlayer == null ? defId : defenderPlayer.getName()));
        }

        logger.info("Kanna declared " + declared.size() + " attacker(s) via " + OLLAMA_MODEL
                + (declaredSummary.isEmpty() ? "" : ": " + String.join(", ", declaredSummary)));
        recordHistory(game, declaredSummary.isEmpty() ? "declared no attacks" : String.join(", ", declaredSummary));
    }

    private void recordHistory(Game game, String summary) {
        combatHistory.addLast("T" + game.getTurnNum() + ": " + summary);
        while (combatHistory.size() > MAX_HISTORY_ENTRIES) {
            combatHistory.removeFirst();
        }
    }

    /**
     * @return the "attacks" tool-call arguments object, or null if the model
     * chose not to attack (or gave nothing usable, treated the same way).
     */
    private JsonObject callOllamaForAttackDecision(String prompt) throws Exception {
        JsonObject function = new JsonObject();
        function.addProperty("name", "declare_attackers");
        function.addProperty("description", "Choose which creatures attack and who they attack this combat.");

        JsonObject pairSchema = new JsonObject();
        pairSchema.addProperty("type", "object");
        JsonObject pairProps = new JsonObject();
        pairProps.add("attacker_id", jsonType("string"));
        pairProps.add("defender_id", jsonType("string"));
        pairSchema.add("properties", pairProps);
        JsonArray pairRequired = new JsonArray();
        pairRequired.add("attacker_id");
        pairRequired.add("defender_id");
        pairSchema.add("required", pairRequired);

        JsonObject attacksSchema = new JsonObject();
        attacksSchema.addProperty("type", "array");
        attacksSchema.add("items", pairSchema);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject topProps = new JsonObject();
        topProps.add("attacks", attacksSchema);
        parameters.add("properties", topProps);
        JsonArray topRequired = new JsonArray();
        topRequired.add("attacks");
        parameters.add("required", topRequired);
        function.add("parameters", parameters);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        JsonArray tools = new JsonArray();
        tools.add(tool);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", OLLAMA_MODEL);
        body.add("messages", messages);
        body.add("tools", tools);
        body.addProperty("stream", false);

        String responseBody = postJson(OLLAMA_URL, body.toString());
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject responseMessage = responseJson.getAsJsonObject("message");
        String thinking = responseMessage.has("thinking") ? responseMessage.get("thinking").getAsString() : null;
        if (thinking != null && !thinking.isEmpty()) {
            logger.info("Kanna thinking: " + thinking);
        }

        JsonArray toolCalls = responseMessage.getAsJsonArray("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null; // model decided not to call the tool at all - treat as "attack with nothing"
        }

        JsonObject firstCall = toolCalls.get(0).getAsJsonObject().getAsJsonObject("function");
        return firstCall.getAsJsonObject("arguments");
    }

    private static String postJson(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setConnectTimeout(REQUEST_TIMEOUT_MS);
        conn.setReadTimeout(REQUEST_TIMEOUT_MS);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status == 200 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody;
        try (InputStream in = stream) {
            responseBody = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
        }
        if (status != 200) {
            throw new IOException("Ollama returned HTTP " + status + ": " + responseBody);
        }
        return responseBody;
    }

    private static JsonObject jsonType(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }
}
