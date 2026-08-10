package mage.player.ai.kanna;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer;
import mage.players.Player;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kanna: a fully agentic, LLM-driven Magic player.
 * <p>
 * Heuristics compute and the model judges. Before any decision reaches the model,
 * CombatEvaluator and ActionRanker enumerate and annotate every legal option with
 * its exact consequences; the model then picks one, using read-only inspection
 * tools if it wants to look deeper. The same heuristics stand in when the model
 * fails, so a failure is a reasonable move plus a metric, never a silent pass.
 * <p>
 * Extends ComputerPlayer, NOT ComputerPlayerMCTS/6/7: there is no search here.
 * Note that ComputerPlayer.priority() is a no-op that just passes -- overriding it
 * is mandatory, and forgetting to is silent rather than loud.
 *
 * @author Darrell Best
 */
public class ComputerPlayerKanna extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(ComputerPlayerKanna.class);

    private static final int MAX_TOOL_CALLS = 4;
    private static final int SHORTLIST_SIZE = 8;
    private static final int MAX_HISTORY_ENTRIES = 5;

    /**
     * Instrumentation callback the benchmark harness supplies. Declared here rather
     * than imported from Mage.Bench because Mage.Bench depends on this module, not
     * the other way round -- the reverse would be a Maven cycle. No-ops when unset.
     */
    public interface DecisionMetrics {
        void recordLlmCall(long latencyMs);

        void recordInvalidToolCall();
    }

    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "xmage-ai-qwen3.6:latest";
    private DecisionMetrics metrics;
    private final Deque<String> combatHistory = new ArrayDeque<String>();

    public ComputerPlayerKanna(String name, RangeOfInfluence range, int skill) {
        // skill is accepted and ignored: it meant search depth/think time, and there
        // is no search any more. Kept so PlayerFactory and the server need no change.
        super(name, range);
    }

    public ComputerPlayerKanna(final ComputerPlayerKanna player) {
        super(player);
        this.ollamaUrl = player.ollamaUrl;
        this.ollamaModel = player.ollamaModel;
        this.metrics = player.metrics;
        this.combatHistory.addAll(player.combatHistory);
    }

    @Override
    public ComputerPlayerKanna copy() {
        return new ComputerPlayerKanna(this);
    }

    public void setOllamaUrl(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl;
    }

    public void setModel(String model) {
        this.ollamaModel = model;
    }

    public String getModel() {
        return ollamaModel;
    }

    public void setBenchMetrics(DecisionMetrics metrics) {
        this.metrics = metrics;
    }

    private KannaAgent newAgent() {
        return new KannaAgent(new OllamaClient(ollamaUrl, ollamaModel), MAX_TOOL_CALLS);
    }

    private void reportInvalid(KannaAgent agent) {
        if (metrics == null) {
            return;
        }
        for (int i = 0; i < agent.getInvalidCount(); i++) {
            metrics.recordInvalidToolCall();
        }
    }

    // ------------------------------------------------------------------ priority

    @Override
    public boolean priority(Game game) {
        List<ActivatedAbility> playable = getPlayable(game, true);

        // Trivial-decision bypass. Most priority windows in Magic offer nothing but
        // Pass; sending each to the model would cost a round trip per window and make
        // the player unusable. This is load-bearing, not an optimisation.
        if (playable.isEmpty() || onlyPass(playable)) {
            pass(game);
            return false;
        }

        ActionCatalog catalog = new ActionCatalog();
        for (ActivatedAbility ability : playable) {
            catalog.add(ability, ability.toString());
        }
        PassAbility passAbility = new PassAbility();
        catalog.add(passAbility, "Pass");

        List<RankedAction> ranked = ActionRanker.rank(catalog);
        String prompt = buildPriorityPrompt(game, ranked, catalog.size());

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt, catalog, new KannaAgent.InspectionAnswerer() {
            @Override
            public String answer(ToolCall call) {
                if ("show_all_actions".equals(call.name)) {
                    return ActionRanker.render(ActionRanker.rank(catalog), catalog.size());
                }
                if ("get_card_text".equals(call.name)) {
                    String id = call.arguments.has("id") ? call.arguments.get("id").getAsString() : null;
                    String label = catalog.labelFor(id);
                    return label == null ? "No such id." : label;
                }
                return null;
            }
        });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            // Heuristics already ranked everything to build the prompt, so the fallback
            // is free and strictly better than passing: take the top-ranked action.
            RankedAction best = ranked.isEmpty() ? null : ranked.get(0);
            ActivatedAbility chosen = best == null ? null : catalog.resolve(best.id);
            if (chosen == null || chosen instanceof PassAbility) {
                pass(game);
                return false;
            }
            logger.info("Kanna: heuristic fallback plays " + best.label);
            activateAbility(chosen, game);
            return true;
        }

        ActivatedAbility chosen = catalog.resolve(decision.chosenId);
        if (chosen instanceof PassAbility) {
            pass(game);
            return false;
        }
        logger.info("Kanna plays " + catalog.labelFor(decision.chosenId) + " via " + ollamaModel);
        activateAbility(chosen, game);
        return true;
    }

    private static boolean onlyPass(List<ActivatedAbility> playable) {
        for (ActivatedAbility ability : playable) {
            if (!(ability instanceof PassAbility)) {
                return false;
            }
        }
        return true;
    }

    private String buildPriorityPrompt(Game game, List<RankedAction> ranked, int total) {
        Player me = game.getPlayer(playerId);
        StringBuilder sb = new StringBuilder();
        sb.append("You are Kanna, playing Magic: The Gathering as ").append(getName())
                .append(" (").append(me == null ? 0 : me.getLife()).append(" life).")
                .append(System.lineSeparator());
        sb.append("Turn ").append(game.getTurnNum()).append(", ").append(game.getStep().getType())
                .append('.').append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("Your creatures: ").append(GameStateFormatter.describeCreatures(myCreatures(game)))
                .append(System.lineSeparator());
        for (UUID opponentId : game.getOpponents(playerId, true)) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null) {
                continue;
            }
            sb.append(opponent.getName()).append(" (").append(opponent.getLife()).append(" life) creatures: ")
                    .append(GameStateFormatter.describeCreatures(creaturesOf(opponentId, game)))
                    .append(System.lineSeparator());
        }
        sb.append(historyBlock());
        sb.append(System.lineSeparator()).append("Your options:").append(System.lineSeparator());
        sb.append(ActionRanker.render(ActionRanker.shortlist(ranked, SHORTLIST_SIZE), total));
        sb.append(System.lineSeparator())
                .append("Call choose_action with exactly one id from the list above.");
        return sb.toString();
    }

    private List<CreatureView> myCreatures(Game game) {
        return creaturesOf(playerId, game);
    }

    private static List<CreatureView> creaturesOf(UUID controllerId, Game game) {
        List<CreatureView> views = new ArrayList<CreatureView>();
        int index = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game)) {
                views.add(CreatureView.from("c-" + index++, permanent, game));
            }
        }
        return views;
    }

    // ------------------------------------------------------------------ targeting

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        List<UUID> possible = new ArrayList<UUID>(target.possibleTargets(getId(), source, game));
        if (possible.size() <= 1) {
            // no real choice to make -- do not spend a model round trip on it
            return super.chooseTarget(outcome, target, source, game);
        }

        Map<String, UUID> byId = new HashMap<String, UUID>();
        ActionCatalog catalog = new ActionCatalog();
        StringBuilder options = new StringBuilder();
        int index = 0;
        for (UUID candidate : possible) {
            String id = "tgt-" + index++;
            byId.put(id, candidate);
            options.append("- ").append(id).append(": ").append(describeTarget(candidate, game))
                    .append(System.lineSeparator());
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Kanna. Choose a target for: ")
                .append(source == null ? "an effect" : source.toString())
                .append(System.lineSeparator())
                .append("Outcome is ").append(outcome).append('.')
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append("Possible targets:").append(System.lineSeparator()).append(options)
                .append(System.lineSeparator())
                .append("Call choose_action with exactly one tgt- id from the list above.");

        for (Map.Entry<String, UUID> entry : byId.entrySet()) {
            catalog.add(new PassAbility(), entry.getKey());
        }

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt.toString(), catalog,
                new KannaAgent.InspectionAnswerer() {
                    @Override
                    public String answer(ToolCall call) {
                        return "show_all_actions".equals(call.name) ? options.toString() : null;
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            return super.chooseTarget(outcome, target, source, game);
        }
        String chosenLabel = catalog.labelFor(decision.chosenId);
        UUID chosen = byId.get(chosenLabel);
        if (chosen == null) {
            return super.chooseTarget(outcome, target, source, game);
        }
        target.addTarget(chosen, source, game);
        logger.info("Kanna targets " + describeTarget(chosen, game));
        return true;
    }

    private static String describeTarget(UUID id, Game game) {
        Permanent permanent = game.getPermanent(id);
        if (permanent != null) {
            return permanent.getName() + " (" + permanent.getPower().getValue()
                    + "/" + permanent.getToughness().getValue() + ")";
        }
        Player player = game.getPlayer(id);
        if (player != null) {
            return "player " + player.getName() + " (" + player.getLife() + " life)";
        }
        return id.toString();
    }

    // ------------------------------------------------------------------ attacks

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, attackingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_ATTACKERS, attackingPlayerId, attackingPlayerId))) {
            return;
        }
        try {
            declareAttacksAgentically(game, attackingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: attack decision failed, deferring to heuristics - " + e, e);
            super.selectAttackers(game, attackingPlayerId);
        }
    }

    private void declareAttacksAgentically(Game game, UUID attackingPlayerId) {
        final Map<String, Permanent> attackers = new HashMap<String, Permanent>();
        final Map<String, UUID> defenders = new HashMap<String, UUID>();
        List<CreatureView> attackerViews = new ArrayList<CreatureView>();
        StringBuilder defenderText = new StringBuilder();

        for (UUID defenderId : game.getOpponents(playerId, true)) {
            Player defender = game.getPlayer(defenderId);
            if (defender == null || !defender.isInGame()) {
                continue;
            }
            String defId = "def-" + defenders.size();
            defenders.put(defId, defenderId);
            List<CreatureView> blockers = untappedCreaturesOf(defenderId, game);
            defenderText.append("- ").append(defId).append(": ").append(defender.getName())
                    .append(" (").append(defender.getLife()).append(" life), possible blockers: ")
                    .append(GameStateFormatter.describeCreatures(blockers))
                    .append(System.lineSeparator());
            for (Permanent attacker : getAvailableAttackers(defenderId, game)) {
                if (!attackers.containsValue(attacker)) {
                    String atkId = "atk-" + attackers.size();
                    attackers.put(atkId, attacker);
                    attackerViews.add(CreatureView.from(atkId, attacker, game));
                }
            }
        }

        if (attackers.isEmpty() || defenders.isEmpty()) {
            return;
        }

        UUID firstDefenderId = defenders.values().iterator().next();
        Player firstDefender = game.getPlayer(firstDefenderId);
        int defenderLife = firstDefender == null ? 20 : firstDefender.getLife();
        String optionText = GameStateFormatter.attackOptions(attackerViews,
                untappedCreaturesOf(firstDefenderId, game), defenderLife);

        StringBuilder prompt = new StringBuilder();
        Player me = game.getPlayer(playerId);
        prompt.append("You are Kanna, playing as ").append(getName())
                .append(" (").append(me == null ? 0 : me.getLife()).append(" life). It is your combat step.")
                .append(System.lineSeparator()).append(historyBlock())
                .append(System.lineSeparator()).append("Your possible attacks, with computed outcomes:")
                .append(System.lineSeparator()).append(optionText)
                .append(System.lineSeparator()).append("Defenders:").append(System.lineSeparator())
                .append(defenderText).append(System.lineSeparator())
                .append("Call declare_attackers using only the ids above. An empty list means attack with nobody.");

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.choosePairs(prompt.toString(), "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String attackerId, String defenderId) {
                        return attackers.containsKey(attackerId) && defenders.containsKey(defenderId);
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            logger.info("Kanna: deferring attacks to heuristics");
            super.selectAttackers(game, attackingPlayerId);
            return;
        }

        Player attackingPlayer = game.getPlayer(attackingPlayerId);
        List<String> summary = new ArrayList<String>();
        List<UUID> declared = new ArrayList<UUID>();
        for (String[] pair : decision.pairs) {
            Permanent attacker = attackers.get(pair[0]);
            UUID defenderId = defenders.get(pair[1]);
            if (attacker == null || defenderId == null || declared.contains(attacker.getId())) {
                continue;
            }
            attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
            declared.add(attacker.getId());
            summary.add(attacker.getName());
        }
        logger.info("Kanna attacks with " + declared.size() + " creature(s) via " + ollamaModel
                + (summary.isEmpty() ? "" : ": " + join(summary)));
        recordHistory(game, "attack", summary.isEmpty() ? "no attacks" : join(summary));
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, defendingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_BLOCKERS, defendingPlayerId, defendingPlayerId))) {
            return;
        }
        try {
            declareBlocksAgentically(source, game, defendingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: block decision failed, deferring to heuristics - " + e, e);
            super.selectBlockers(source, game, defendingPlayerId);
        }
    }

    private void declareBlocksAgentically(Ability source, Game game, UUID defendingPlayerId) {
        final Map<String, Permanent> attackers = new HashMap<String, Permanent>();
        final Map<String, Permanent> blockers = new HashMap<String, Permanent>();
        List<CreatureView> attackerViews = new ArrayList<CreatureView>();
        List<CreatureView> blockerViews = new ArrayList<CreatureView>();

        for (UUID attackerId : game.getCombat().getAttackers()) {
            if (!defendingPlayerId.equals(game.getCombat().getDefendingPlayerId(attackerId, game))) {
                continue;
            }
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker == null) {
                continue;
            }
            String id = "atk-" + attackers.size();
            attackers.put(id, attacker);
            attackerViews.add(CreatureView.from(id, attacker, game));
        }
        if (attackers.isEmpty()) {
            return;
        }

        for (Permanent blocker : getAvailableBlockers(game)) {
            boolean canBlockSomething = false;
            for (Permanent attacker : attackers.values()) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    canBlockSomething = true;
                    break;
                }
            }
            if (canBlockSomething) {
                String id = "blk-" + blockers.size();
                blockers.put(id, blocker);
                blockerViews.add(CreatureView.from(id, blocker, game));
            }
        }
        if (blockers.isEmpty()) {
            recordHistory(game, "block", "no legal blockers");
            return;
        }

        Player me = game.getPlayer(playerId);
        int myLife = me == null ? 20 : me.getLife();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Kanna, playing as ").append(getName())
                .append(" (").append(myLife).append(" life). You are being attacked.")
                .append(System.lineSeparator()).append(historyBlock())
                .append(System.lineSeparator()).append("Attacking you:").append(System.lineSeparator())
                .append(GameStateFormatter.attackOptions(attackerViews, blockerViews, myLife))
                .append(System.lineSeparator()).append("Your available blockers: ")
                .append(GameStateFormatter.describeCreatures(blockerViews))
                .append(System.lineSeparator()).append(System.lineSeparator())
                .append("Call declare_blockers using only the ids above. An empty list means block with nobody.");

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.choosePairs(prompt.toString(), "declare_blockers", "blocks",
                "blocker_id", "attacker_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String blockerId, String attackerId) {
                        Permanent blocker = blockers.get(blockerId);
                        Permanent attacker = attackers.get(attackerId);
                        return blocker != null && attacker != null;
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            logger.info("Kanna: deferring blocks to heuristics");
            super.selectBlockers(source, game, defendingPlayerId);
            return;
        }

        Player defendingPlayer = game.getPlayer(defendingPlayerId);
        List<UUID> used = new ArrayList<UUID>();
        List<String> summary = new ArrayList<String>();
        for (String[] pair : decision.pairs) {
            Permanent blocker = blockers.get(pair[0]);
            Permanent attacker = attackers.get(pair[1]);
            if (blocker == null || attacker == null || used.contains(blocker.getId())
                    || !blocker.canBlock(attacker.getId(), game)) {
                continue;
            }
            defendingPlayer.declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
            used.add(blocker.getId());
            summary.add(blocker.getName() + " blocks " + attacker.getName());
        }
        logger.info("Kanna blocks with " + used.size() + " creature(s) via " + ollamaModel);
        recordHistory(game, "block", summary.isEmpty() ? "no blocks" : join(summary));
    }

    // ------------------------------------------------------------------ helpers

    private static List<CreatureView> untappedCreaturesOf(UUID controllerId, Game game) {
        List<CreatureView> views = new ArrayList<CreatureView>();
        int index = 0;
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(controllerId)) {
            if (permanent.isCreature(game) && !permanent.isTapped()) {
                views.add(CreatureView.from("blk-" + index++, permanent, game));
            }
        }
        return views;
    }

    private void recordLatency(long startNanos) {
        if (metrics != null) {
            metrics.recordLlmCall((System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    private String historyBlock() {
        if (combatHistory.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Your recent decisions:").append(System.lineSeparator());
        for (String entry : combatHistory) {
            sb.append("  ").append(entry).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private void recordHistory(Game game, String kind, String summary) {
        combatHistory.addLast("T" + game.getTurnNum() + " (" + kind + "): " + summary);
        while (combatHistory.size() > MAX_HISTORY_ENTRIES) {
            combatHistory.removeFirst();
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
