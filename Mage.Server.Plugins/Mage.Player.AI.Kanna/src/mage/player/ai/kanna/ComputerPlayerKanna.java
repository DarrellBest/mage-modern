package mage.player.ai.kanna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.abilities.Ability;
import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.DoubleStrikeAbility;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.MenaceAbility;
import mage.abilities.keyword.ReachAbility;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayerMCTS;
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
 * AI player: Monte Carlo Tree Search (ComputerPlayerMCTS) for everything,
 * except attack and block declaration, which are decided by an LLM (via
 * Ollama tool calling) instead.
 * <p>
 * Extends ComputerPlayerMCTS rather than ComputerPlayer6/7's exhaustive
 * minimax. Minimax's cost is (branching factor)^depth -- even a capped
 * branching factor still compounds exponentially across maxDepth (== skill,
 * 6-8 for a "hard" AI), which is what pegged 18 of 24 CPU cores for 4+
 * minutes straight on a 50-permanent board and made the server unresponsive
 * to every client. MCTS instead runs a fixed time/iteration budget of
 * sampled rollouts -- cost is bounded by that budget, not by how deep the
 * tree could theoretically go, so a huge board just means cruder rollouts
 * within the same budget instead of exponential blowup.
 * <p>
 * Isolated in its own plugin module on purpose -- never touches shared
 * engine files, so pulling from upstream never conflicts with this class.
 *
 * @author Darrell Best
 */
public class ComputerPlayerKanna extends ComputerPlayerMCTS {

    private static final Logger logger = Logger.getLogger(ComputerPlayerKanna.class);

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String OLLAMA_MODEL = "deepseek-v4-pro:cloud";
    private static final int REQUEST_TIMEOUT_MS = 30_000;

    // kept small on purpose: this gets re-sent in full with every prompt, so history length
    // is a direct, ongoing token cost, not a one-time one. Shared between attacks and blocks
    // rather than tracked separately, to avoid doubling that recurring cost.
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

    // ---------------------------------------------------------------- attacks

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
            defendersDesc.append(boardStateSummary(defenderId, game));

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

        Player me = game.getPlayer(playerId);
        String prompt = String.format(
                "You are Kanna, playing Magic: The Gathering as %s (%d life). It's your combat step. Decide which of "
                        + "your available creatures should attack, and who each one attacks. Each defender's possible "
                        + "blockers and other permanents are listed so you can judge whether an attack is actually safe. "
                        + "It's fine to attack with none of them if that's the better play.%n%n%sYour available attackers:"
                        + "%n%s%nPossible defenders:%n%s%n"
                        + "Call declare_attackers using only the short ids listed above.",
                getName(), me == null ? 0 : me.getLife(), historyBlock(), attackersDesc, defendersDesc
        );

        JsonObject toolCall = callOllamaForDecision(
                "declare_attackers", "Choose which creatures attack and who they attack this combat.",
                pairArraySchema("attacks", "attacker_id", "defender_id"), prompt
        );
        if (toolCall == null) {
            logger.info("Kanna: model chose not to attack this combat");
            recordHistory(game, "attack", "declared no attacks");
            return;
        }

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
        recordHistory(game, "attack", declaredSummary.isEmpty() ? "declared no attacks" : String.join(", ", declaredSummary));
    }

    // ---------------------------------------------------------------- blocks

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        logger.info("Kanna: selectBlockers called for " + getName());

        // same commit-once protocol as selectAttackers -- see the comment there
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, defendingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, defendingPlayerId, defendingPlayerId))) {
            logger.info("Kanna: declaring blockers was replaced/prevented this combat");
            return;
        }

        try {
            chooseBlockersWithKanna(game, defendingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: LLM block decision failed, declaring no blocks this combat - " + e, e);
        }
    }

    private void chooseBlockersWithKanna(Game game, UUID defendingPlayerId) throws Exception {
        Map<String, Permanent> attackersById = new HashMap<>();
        StringBuilder attackersDesc = new StringBuilder();
        for (UUID attackerId : game.getCombat().getAttackers()) {
            if (!defendingPlayerId.equals(game.getCombat().getDefendingPlayerId(attackerId, game))) {
                continue; // attacking someone else, not relevant to my blocks
            }
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker == null) {
                continue;
            }
            String atkId = "atk-" + attackersById.size();
            attackersById.put(atkId, attacker);
            attackersDesc.append(String.format("- %s: %s (%d/%d)%n",
                    atkId, attacker.getName(), attacker.getPower().getValue(), attacker.getToughness().getValue()));
        }

        if (attackersById.isEmpty()) {
            logger.info("Kanna: nothing attacking me this combat, nothing to block");
            return;
        }

        Map<String, Permanent> blockersById = new HashMap<>();
        StringBuilder blockersDesc = new StringBuilder();
        for (Permanent blocker : getAvailableBlockers(game)) {
            boolean canBlockSomething = attackersById.values().stream().anyMatch(a -> blocker.canBlock(a.getId(), game));
            if (!canBlockSomething) {
                continue;
            }
            String blkId = "blk-" + blockersById.size();
            blockersById.put(blkId, blocker);
            blockersDesc.append(String.format("- %s: %s (%d/%d)%n",
                    blkId, blocker.getName(), blocker.getPower().getValue(), blocker.getToughness().getValue()));
        }

        if (blockersById.isEmpty()) {
            logger.info("Kanna: no legal blockers available, taking the damage");
            recordHistory(game, "block", "declared no blocks (nothing could legally block)");
            return;
        }

        String prompt = String.format(
                "You are Kanna, playing Magic: The Gathering as %s. You're being attacked. Decide which of your "
                        + "available creatures should block, and which attacker each one blocks. More than one of your "
                        + "creatures can block the same attacker. It's fine to leave attackers unblocked if that's the "
                        + "better play.%n%n%sAttacking you:%n%sYour available blockers:%n%s%n"
                        + "Call declare_blockers using only the short ids listed above.",
                getName(), historyBlock(), attackersDesc, blockersDesc
        );

        JsonObject toolCall = callOllamaForDecision(
                "declare_blockers", "Choose which of your creatures block, and which attacker each one blocks.",
                pairArraySchema("blocks", "blocker_id", "attacker_id"), prompt
        );
        if (toolCall == null) {
            logger.info("Kanna: model chose not to block this combat");
            recordHistory(game, "block", "declared no blocks");
            return;
        }

        JsonArray blocks = toolCall.getAsJsonArray("blocks");
        if (blocks == null) {
            logger.warn("Kanna: tool-call response had no 'blocks' array, declaring no blocks");
            return;
        }

        Player defendingPlayer = game.getPlayer(defendingPlayerId);
        List<UUID> usedBlockers = new ArrayList<>();
        List<String> declaredSummary = new ArrayList<>();
        for (JsonElement el : blocks) {
            JsonObject pair = el.getAsJsonObject();
            String blkId = pair.has("blocker_id") ? pair.get("blocker_id").getAsString() : null;
            String atkId = pair.has("attacker_id") ? pair.get("attacker_id").getAsString() : null;
            Permanent blocker = blkId == null ? null : blockersById.get(blkId);
            Permanent attacker = atkId == null ? null : attackersById.get(atkId);
            boolean legal = blocker != null && attacker != null && blocker.canBlock(attacker.getId(), game);
            if (blocker == null || attacker == null || usedBlockers.contains(blocker.getId()) || !legal) {
                logger.warn("Kanna: ignoring invalid/hallucinated block pair from LLM: " + blkId + " -> " + atkId);
                continue;
            }
            defendingPlayer.declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
            usedBlockers.add(blocker.getId());
            declaredSummary.add(blocker.getName() + " blocks " + attacker.getName());
        }

        logger.info("Kanna declared " + usedBlockers.size() + " blocker(s) via " + OLLAMA_MODEL
                + (declaredSummary.isEmpty() ? "" : ": " + String.join(", ", declaredSummary)));
        recordHistory(game, "block", declaredSummary.isEmpty() ? "declared no blocks" : String.join(", ", declaredSummary));
    }

    // ---------------------------------------------------------------- shared helpers

    private String historyBlock() {
        // compact on purpose -- one line per past decision, no rationale/thinking text -- since
        // this gets re-sent with every single prompt, not just paid for once
        return combatHistory.isEmpty()
                ? ""
                : "Your recent combat decisions:\n" + String.join("\n", combatHistory) + "\n\n";
    }

    private void recordHistory(Game game, String kind, String summary) {
        combatHistory.addLast("T" + game.getTurnNum() + " (" + kind + "): " + summary);
        while (combatHistory.size() > MAX_HISTORY_ENTRIES) {
            combatHistory.removeFirst();
        }
    }

    /**
     * Compact "what does this player actually have" block: their untapped creatures (i.e. their
     * real possible blockers -- a tapped creature can't block, so there's no reason to list it
     * and burn tokens on it) and any other permanents they control, so attack decisions aren't
     * made blind to what's actually on the other side of the table.
     */
    private static String boardStateSummary(UUID controllerId, Game game) {
        List<String> blockerLines = new ArrayList<>();
        List<String> otherPermanents = new ArrayList<>();
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game)) {
                if (!permanent.isTapped()) {
                    blockerLines.add(permanent.getName()
                            + " (" + permanent.getPower().getValue() + "/" + permanent.getToughness().getValue() + ")"
                            + keywordSummary(permanent, game));
                }
            } else {
                otherPermanents.add(permanent.getName());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  Possible blockers: ").append(blockerLines.isEmpty() ? "none" : String.join(", ", blockerLines)).append(System.lineSeparator());
        if (!otherPermanents.isEmpty()) {
            sb.append("  Other permanents: ").append(String.join(", ", otherPermanents)).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static String keywordSummary(Permanent permanent, Game game) {
        List<String> keywords = new ArrayList<>();
        if (permanent.getAbilities(game).containsClass(FlyingAbility.class)) {
            keywords.add("Flying");
        }
        if (permanent.getAbilities(game).containsClass(ReachAbility.class)) {
            keywords.add("Reach");
        }
        if (permanent.getAbilities(game).containsClass(MenaceAbility.class)) {
            keywords.add("Menace");
        }
        if (permanent.getAbilities(game).containsClass(DeathtouchAbility.class)) {
            keywords.add("Deathtouch");
        }
        if (permanent.getAbilities(game).containsClass(FirstStrikeAbility.class)) {
            keywords.add("First Strike");
        }
        if (permanent.getAbilities(game).containsClass(DoubleStrikeAbility.class)) {
            keywords.add("Double Strike");
        }
        return keywords.isEmpty() ? "" : " [" + String.join(", ", keywords) + "]";
    }

    private static JsonObject pairArraySchema(String arrayName, String field1, String field2) {
        JsonObject pairSchema = new JsonObject();
        pairSchema.addProperty("type", "object");
        JsonObject pairProps = new JsonObject();
        pairProps.add(field1, jsonType("string"));
        pairProps.add(field2, jsonType("string"));
        pairSchema.add("properties", pairProps);
        JsonArray pairRequired = new JsonArray();
        pairRequired.add(field1);
        pairRequired.add(field2);
        pairSchema.add("required", pairRequired);

        JsonObject arraySchema = new JsonObject();
        arraySchema.addProperty("type", "array");
        arraySchema.add("items", pairSchema);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject topProps = new JsonObject();
        topProps.add(arrayName, arraySchema);
        parameters.add("properties", topProps);
        JsonArray topRequired = new JsonArray();
        topRequired.add(arrayName);
        parameters.add("required", topRequired);
        return parameters;
    }

    /**
     * @return the tool call's arguments object, or null if the model chose not
     * to call the tool at all (treated as "do nothing" either way).
     */
    private JsonObject callOllamaForDecision(String toolName, String toolDescription, JsonObject parameters, String prompt) throws Exception {
        JsonObject function = new JsonObject();
        function.addProperty("name", toolName);
        function.addProperty("description", toolDescription);
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

        logger.info("Kanna: prompt sent to " + OLLAMA_MODEL + " for " + getName() + " (" + toolName + "):\n" + prompt);

        String responseBody = postJson(OLLAMA_URL, body.toString());
        JsonObject responseJson = JsonParser.parseString(responseBody).getAsJsonObject();

        int promptTokens = responseJson.has("prompt_eval_count") ? responseJson.get("prompt_eval_count").getAsInt() : -1;
        int completionTokens = responseJson.has("eval_count") ? responseJson.get("eval_count").getAsInt() : -1;
        logger.info("Kanna: token usage for " + toolName + " - prompt=" + promptTokens
                + " completion=" + completionTokens + " total=" + (promptTokens + completionTokens));

        JsonObject responseMessage = responseJson.getAsJsonObject("message");
        String thinking = responseMessage.has("thinking") ? responseMessage.get("thinking").getAsString() : null;
        if (thinking != null && !thinking.isEmpty()) {
            logger.info("Kanna thinking: " + thinking);
        }

        JsonArray toolCalls = responseMessage.getAsJsonArray("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null; // model decided not to call the tool at all
        }

        JsonObject firstCall = toolCalls.get(0).getAsJsonObject().getAsJsonObject("function");
        JsonObject arguments = firstCall.getAsJsonObject("arguments");
        logger.info("Kanna: raw tool-call arguments: " + arguments);
        return arguments;
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
