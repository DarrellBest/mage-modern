package mage.player.ai.kanna;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.mana.ManaAbility;
import mage.cards.Card;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // capped retries when activateAbility() fails, on top of the initial attempt -- see
    // activateWithFallback
    private static final int MAX_ACTIVATION_RETRIES = 2;

    /**
     * Instrumentation callback the benchmark harness supplies. Declared here rather
     * than imported from Mage.Bench because Mage.Bench depends on this module, not
     * the other way round -- the reverse would be a Maven cycle. No-ops when unset.
     */
    public interface DecisionMetrics {
        void recordLlmCall(long latencyMs);

        void recordInvalidToolCall();
    }

    // DARRELLBEST-FORK: both InspectionAnswerers below (priority()'s and
    // chooseOneTargetAgentically()'s) handle every read-only inspection tool
    // KannaAgent knows about -- this is the advertise/answer coupling described on
    // KannaAgent.InspectionAnswerer.supportedTools(): declaring a tool here without
    // handling it in answer() (or vice versa) is exactly the bug that made
    // get_card_text advertised-but-unhandled in the targeting path.
    private static final Set<String> ALL_INSPECTION_TOOLS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(KannaAgent.TOOL_GET_CARD_TEXT, KannaAgent.TOOL_SHOW_ALL_ACTIONS)));

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

    // DARRELLBEST-FORK: protected rather than private specifically as a test seam --
    // lets a test subclass override this to hand back a KannaAgent wired to a scripted
    // OllamaClient instead of a real one (see KannaAgentTest's ScriptedClient for the
    // same pattern one layer down), exercising the real decision code in
    // priority()/chooseTarget()/declareAttacksAgentically()/declareBlocksAgentically()
    // without any network call. No behavior change for real (non-test) use.
    protected KannaAgent newAgent() {
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

        // Mana abilities ({T}: Add {G}. and the like) are deliberately excluded from
        // the catalog entirely, not merely ranked low. getPlayable() returns them as
        // ordinary top-level actions, indistinguishable in shape from casting a spell,
        // and offering "tap this land for mana" as its own choice invites exactly the
        // failure this caused in testing: the model tapped a land for mana it had
        // nothing to spend on, which permanently cost that land's availability for the
        // rest of the turn and made the creature it should have cast unaffordable one
        // priority window later. Casting/activating a real ability already auto-pays
        // its cost by tapping producers as needed -- there is never a legitimate reason
        // for Kanna to activate a mana ability as a standalone top-level choice.
        List<ActivatedAbility> catalogable = new ArrayList<ActivatedAbility>();
        for (ActivatedAbility ability : playable) {
            if (!(ability instanceof ManaAbility)) {
                catalogable.add(ability);
            }
        }

        // Trivial-decision bypass. Most priority windows in Magic offer nothing but
        // Pass; sending each to the model would cost a round trip per window and make
        // the player unusable. This is load-bearing, not an optimisation.
        if (catalogable.isEmpty() || onlyPass(catalogable)) {
            pass(game);
            return false;
        }

        ActionCatalog catalog = new ActionCatalog();
        for (ActivatedAbility ability : catalogable) {
            catalog.add(ability, labelFor(ability, game));
        }
        PassAbility passAbility = new PassAbility();
        catalog.add(passAbility, "Pass");

        List<RankedAction> ranked = ActionRanker.rank(catalog);
        String prompt = buildPriorityPrompt(game, ranked, catalog.size());

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt, catalog, new KannaAgent.InspectionAnswerer() {
            @Override
            public Set<String> supportedTools() {
                return ALL_INSPECTION_TOOLS;
            }

            @Override
            public String answer(ToolCall call) {
                if (KannaAgent.TOOL_SHOW_ALL_ACTIONS.equals(call.name)) {
                    return ActionRanker.render(ActionRanker.rank(catalog), catalog.size());
                }
                if (KannaAgent.TOOL_GET_CARD_TEXT.equals(call.name)) {
                    String id = call.arguments != null && call.arguments.has("id")
                            ? call.arguments.get("id").getAsString() : null;
                    return describeActionCardText(id, catalog, game);
                }
                return null;
            }
        });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            // Heuristics already ranked everything to build the prompt, so the fallback
            // is free: take the top-ranked action. That is not always a real action any
            // more (ActionRanker.SCORE_OTHER now scores below Pass -- see its own
            // comment), and when it is Pass that is correct too: this bucket is
            // unrecognised-to-the-ranker activated abilities, which are exactly as
            // likely to be a wasted cost as a fine one, so defaulting to safe-and-inert
            // beats guessing.
            RankedAction best = ranked.isEmpty() ? null : ranked.get(0);
            ActivatedAbility chosen = best == null ? null : catalog.resolve(best.id);
            if (chosen == null || chosen instanceof PassAbility) {
                pass(game);
                return false;
            }
            logger.info("Kanna: heuristic fallback plays " + best.label);
            return activateWithFallback(chosen, best.id, ranked, catalog, game);
        }

        ActivatedAbility chosen = catalog.resolve(decision.chosenId);
        if (chosen instanceof PassAbility) {
            pass(game);
            return false;
        }
        logger.info("Kanna plays " + catalog.labelFor(decision.chosenId) + " via " + ollamaModel);
        return activateWithFallback(chosen, decision.chosenId, ranked, catalog, game);
    }

    // DARRELLBEST-FORK: activateAbility's boolean return used to be discarded here, with
    // priority() returning true regardless. On failure that left getPlayable() offering
    // the identical action on the very next pass through GameImpl.playPriority's inner
    // loop (it re-invokes player.priority() as long as isPassed() is false, which a bare
    // "return true" never sets), so ActionRanker ranked the same doomed action first
    // again and the model paid a full round trip to re-pick it -- observed live as Grim
    // Backwoods ({T}: Draw a card) activated 6 times in one game despite being a once-
    // per-turn ability. Walking down the already-computed `ranked` list on failure costs
    // nothing extra from the model (no new round trip, `ranked` was built once for the
    // prompt) and actually terminates the loop: once every non-Pass candidate has either
    // been tried or excluded, this passes -- which sets isPassed() and is what actually
    // stops GameImpl from asking again immediately.
    private boolean activateWithFallback(ActivatedAbility first, String firstId,
                                         List<RankedAction> ranked, ActionCatalog catalog, Game game) {
        Set<String> failed = new HashSet<String>();
        ActivatedAbility candidate = first;
        String candidateId = firstId;
        for (int attempt = 0; candidate != null && attempt <= MAX_ACTIVATION_RETRIES; attempt++) {
            if (activateAbility(candidate, game)) {
                return true;
            }
            logger.warn("Kanna: activation failed for " + catalog.labelFor(candidateId)
                    + (attempt < MAX_ACTIVATION_RETRIES ? ", trying the next best option" : ", giving up"));
            if (metrics != null) {
                metrics.recordInvalidToolCall();
            }
            failed.add(candidateId);
            RankedAction next = nextNonPassCandidate(ranked, catalog, failed);
            if (next == null) {
                break;
            }
            candidateId = next.id;
            candidate = catalog.resolve(candidateId);
        }
        pass(game);
        return false;
    }

    private static RankedAction nextNonPassCandidate(List<RankedAction> ranked, ActionCatalog catalog,
                                                      Set<String> excluded) {
        for (RankedAction action : ranked) {
            if (excluded.contains(action.id)) {
                continue;
            }
            ActivatedAbility ability = catalog.resolve(action.id);
            if (ability != null && !(ability instanceof PassAbility)) {
                return action;
            }
        }
        return null;
    }

    // DARRELLBEST-FORK: see GameStateFormatter.counterAnnotation's javadoc for why this
    // is not folded into ability.toString() itself -- the annotation needs the source
    // Permanent (for its current counters) and the Game (to read them), neither of
    // which Ability.toString() has access to. ActionEvaluator.annotate() is the single
    // consolidated place that decides the rest of this suffix too (counters plus cost
    // consequences -- sacrifice/life/discard) rather than this method calling
    // GameStateFormatter directly and a second, separate place computing cost math.
    private String labelFor(ActivatedAbility ability, Game game) {
        String label = ability.toString();
        return label + ActionEvaluator.annotate(ability, game, playerId);
    }

    // DARRELLBEST-FORK: answers get_card_text in the priority path. This used to just
    // return catalog.labelFor(id) -- the exact shortlist line the model already had --
    // so a model asking for more detail got back nothing new, then asked again until it
    // burned the whole call cap without ever committing (observed live: 3 of 12
    // decisions in one game ended in cap exhaustion, all traced to this). Resolves the
    // id to the real game object via the ability's source and returns its actual oracle
    // text plus current counters instead.
    private static String describeActionCardText(String id, ActionCatalog catalog, Game game) {
        ActivatedAbility ability = catalog.resolve(id);
        if (ability == null) {
            return "No such id: " + id + ".";
        }
        UUID sourceId = ability.getSourceId();
        Card card = resolveCard(sourceId, game);
        if (card == null) {
            // Genuinely unresolvable (e.g. Pass, or a source that has since left the
            // game) -- say so plainly rather than returning null, which the agent would
            // otherwise read as "unknown tool" and count as a model error it did not
            // commit.
            return "No further text available for " + id + ".";
        }
        return describeCardFully(card, game);
    }

    // DARRELLBEST-FORK: answers get_card_text in the targeting path (chooseTarget's
    // chooseOneTargetAgentically). That answerer used to handle only show_all_actions
    // and return null for everything else -- including get_card_text, which is
    // advertised to the model by KannaAgent right alongside show_all_actions. A null
    // answer to an advertised tool call reads as "unknown tool" one layer up: an
    // immediate Decision.fallback() plus an invalidCount++ for using a tool the agent
    // itself told the model existed, corrupting BenchMetrics' model-quality metric.
    // byId here maps the synthetic per-call ids back to the real candidate UUID
    // (permanent or player) -- targets are not ActivatedAbility-backed the way
    // priority()'s catalog is (chooseOneTargetAgentically wraps each candidate in a
    // throwaway PassAbility purely to reuse ActionCatalog's id bookkeeping), so
    // resolution goes through that map rather than catalog.resolve(id)/getSourceId().
    private static String describeTargetCardText(String id, Map<String, UUID> byId, Game game) {
        UUID targetId = id == null ? null : byId.get(id);
        if (targetId == null) {
            return "No such id: " + id + ".";
        }
        Permanent permanent = game.getPermanent(targetId);
        if (permanent != null) {
            return describeCardFully(permanent, game);
        }
        Player player = game.getPlayer(targetId);
        if (player != null) {
            return "Player " + player.getName() + " (" + player.getLife() + " life).";
        }
        return "No further text available for " + id + ".";
    }

    private static Card resolveCard(UUID sourceId, Game game) {
        if (sourceId == null) {
            return null;
        }
        Permanent permanent = game.getPermanent(sourceId);
        if (permanent != null) {
            return permanent;
        }
        return game.getCard(sourceId);
    }

    // DARRELLBEST-FORK: the actual "more detail than the shortlist label" text -- oracle
    // text via getRules(Game), which is the effect-modified/current text (not the
    // static base text getRules() with no argument would give), plus counters via the
    // same GameStateFormatter annotation the shortlist label uses. Counters matter here
    // for the same reason GameStateFormatter.counterAnnotation's own javadoc gives: an
    // ability's real value can depend on a counter count the label alone never showed
    // (Jar of Eyeballs's X, for one).
    private static String describeCardFully(Card card, Game game) {
        StringBuilder sb = new StringBuilder(card.getName()).append(':');
        for (String rule : card.getRules(game)) {
            sb.append(' ').append(rule);
        }
        if (card instanceof Permanent) {
            sb.append(GameStateFormatter.counterAnnotation((Permanent) card, sb.toString(), game));
        }
        return sb.toString();
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
        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());

        // Loop rather than add-one-and-return: a target requiring more than one pick
        // (e.g. "target two creatures") is not complete after a single addTarget, and
        // ComputerPlayer.makeChoice re-checks isChoiceCompleted after every add for
        // exactly this reason. Each iteration re-derives the possible-targets pool, since
        // choosing one candidate can make it (and sometimes others) ineligible next time.
        while (!target.isChoiceCompleted(abilityControllerId, source, game, null)) {
            List<UUID> possible = new ArrayList<UUID>(target.possibleTargets(getId(), source, game));
            if (possible.isEmpty()) {
                break;
            }

            UUID chosen;
            if (possible.size() == 1) {
                // no real choice to make -- do not spend a model round trip on it
                chosen = possible.get(0);
            } else {
                chosen = chooseOneTargetAgentically(outcome, target, source, game, possible);
                if (chosen == null) {
                    // model failed on this slot -- stop rather than loop forever
                    break;
                }
            }

            // addTarget can be a silent no-op: TargetImpl.addTarget fires a TargetEvent
            // that a replacement effect (e.g. Fiendslayer Paladin) can veto, in which case
            // nothing is added, isChoiceCompleted stays false, and possibleTargets can
            // return the exact same pool next iteration -- without this guard that is a
            // tight infinite loop on the game thread, never reaching the LLM or a log
            // line. ComputerPlayer's own equivalent loops (TargetImpl.java) carry the same
            // "did the count actually change" guard for the same reason.
            int beforeCount = target.getTargets().size();
            target.addTarget(chosen, source, game);
            if (target.getTargets().size() == beforeCount) {
                break;
            }
        }

        if (target.isChoiceCompleted(abilityControllerId, source, game, null)) {
            return true;
        }
        // Could not complete the target requirement (the model declined/failed on a
        // remaining slot, or ran out of legal candidates). An incomplete target
        // selection is worse than falling back, so let the heuristic player finish it
        // from wherever this left off -- Target tracks what has already been chosen, so
        // this picks up rather than starting over.
        return super.chooseTarget(outcome, target, source, game);
    }

    /**
     * One model round trip to pick a single target from a multi-candidate pool. Returns
     * null on any model failure (fallback), never a Pass or an unresolvable id -- the ids
     * offered to the model are the catalog's own real ids, not a separate synthetic
     * scheme, so a committed choice always resolves.
     */
    private UUID chooseOneTargetAgentically(Outcome outcome, Target target, Ability source, Game game,
                                            List<UUID> possible) {
        ActionCatalog catalog = new ActionCatalog();
        for (UUID candidate : possible) {
            catalog.add(new PassAbility(), describeTarget(candidate, game));
        }
        List<String> ids = catalog.ids();
        final Map<String, UUID> byId = new HashMap<String, UUID>();
        final StringBuilder options = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            byId.put(id, possible.get(i));
            options.append("- ").append(id).append(": ").append(catalog.labelFor(id))
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
                .append("Call choose_action with exactly one act- id from the list above.");

        KannaAgent agent = newAgent();
        long start = System.nanoTime();
        Decision decision = agent.chooseAction(prompt.toString(), catalog,
                new KannaAgent.InspectionAnswerer() {
                    @Override
                    public Set<String> supportedTools() {
                        return ALL_INSPECTION_TOOLS;
                    }

                    @Override
                    public String answer(ToolCall call) {
                        if (KannaAgent.TOOL_SHOW_ALL_ACTIONS.equals(call.name)) {
                            return options.toString();
                        }
                        if (KannaAgent.TOOL_GET_CARD_TEXT.equals(call.name)) {
                            String id = call.arguments != null && call.arguments.has("id")
                                    ? call.arguments.get("id").getAsString() : null;
                            return describeTargetCardText(id, byId, game);
                        }
                        return null;
                    }
                });
        recordLatency(start);
        reportInvalid(agent);

        if (decision.fallback) {
            return null;
        }
        UUID chosen = byId.get(decision.chosenId);
        if (chosen != null) {
            logger.info("Kanna targets " + describeTarget(chosen, game));
        }
        return chosen;
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

    // DARRELLBEST-FORK: do NOT fall back to super.selectAttackers()/super.selectBlockers()
    // anywhere below. ComputerPlayer's own selectAttackers/selectBlockers are empty no-op
    // stubs ("do nothing, parent class must implement it") -- ComputerPlayer6 implements
    // real combat heuristics, ComputerPlayer does not, and Kanna deliberately does not
    // extend ComputerPlayer6/7/MCTS. Falling back to super here would silently declare
    // zero attackers/blockers on every model failure while logging "deferring to
    // heuristics" -- the exact silent no-op this whole class exists to avoid.
    // heuristicAttacks()/heuristicBlocks() below are the real fallback, driven by
    // CombatEvaluator (the same arithmetic that already annotated the prompt).
    //
    // Also: heuristicAttacks()/heuristicBlocks() must NOT re-fire
    // DECLARE_ATTACKERS_STEP_PRE / DECLARE_BLOCKERS_STEP_PRE or re-check replaceEvent --
    // those already fired below, before the try block, and re-firing them from inside a
    // fallback would double-trigger any "before combat" abilities. They declare directly.
    //
    // Also: the heuristic call sits OUTSIDE the try block, exactly once, rather than
    // being called both from within declareAttacksAgentically/declareBlocksAgentically
    // (on decision.fallback) and again from the catch here. declareAttacksAgentically/
    // declareBlocksAgentically instead return a boolean -- true once they have fully
    // handled the decision (agentic or already-deferred-internally), false to ask the
    // caller to run the heuristic. If heuristicAttacks/heuristicBlocks were called
    // directly from inside declareAttacksAgentically/declareBlocksAgentically and then
    // *that call itself* threw, the outer catch here would run the same heuristic a
    // second time on fresh state -- e.g. a vigilance attacker declared twice.

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, attackingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_ATTACKERS, attackingPlayerId, attackingPlayerId))) {
            return;
        }
        boolean handled;
        try {
            handled = declareAttacksAgentically(game, attackingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: attack decision failed, deferring to heuristics - " + e, e);
            handled = false;
        }
        if (!handled) {
            heuristicAttacks(game, attackingPlayerId);
        }
    }

    /**
     * @return true if this call fully handled the decision (agentically, or there was
     * nothing to decide), false if the caller must run heuristicAttacks itself.
     */
    private boolean declareAttacksAgentically(Game game, UUID attackingPlayerId) {
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
            return true;
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
            return false;
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
        return true;
    }

    // ------------------------------------------------------------------ blocks

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, defendingPlayerId));
        if (game.replaceEvent(GameEvent.getEvent(
                GameEvent.EventType.DECLARING_BLOCKERS, defendingPlayerId, defendingPlayerId))) {
            return;
        }
        boolean handled;
        try {
            handled = declareBlocksAgentically(source, game, defendingPlayerId);
        } catch (Throwable e) {
            logger.warn("Kanna: block decision failed, deferring to heuristics - " + e, e);
            handled = false;
        }
        if (!handled) {
            heuristicBlocks(source, game, defendingPlayerId);
        }
    }

    /**
     * @return true if this call fully handled the decision (agentically, or there was
     * nothing to decide), false if the caller must run heuristicBlocks itself.
     */
    private boolean declareBlocksAgentically(Ability source, Game game, UUID defendingPlayerId) {
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
            return true;
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
            return true;
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
            return false;
        }

        Player defendingPlayer = game.getPlayer(defendingPlayerId);

        // First pass: validate each pair (blocker/attacker exist, blocker not already
        // claimed by an earlier pair, legally able to block) and group the survivors by
        // attacker. This does not yet check a minimum-blockers restriction (menace and
        // the like, Permanent.getMinBlockedBy()) -- blocker.canBlock() doesn't either --
        // so a model can legally-per-pair assign a single blocker to a menace attacker.
        // That would reach CombatGroup.checkBlockRestrictions, which rejects a too-small
        // group, and Combat.selectBlockers re-invokes selectBlockers up to 20 times --
        // each retry re-firing DECLARE_BLOCKERS_STEP_PRE and, on this path, re-invoking
        // the LLM -- before throwing IllegalArgumentException in test mode. Grouping
        // first and dropping short groups below catches this for free, before it ever
        // reaches the engine, instead of paying for it in up to 20 wasted model calls.
        Map<String, List<Permanent>> byAttacker = new LinkedHashMap<String, List<Permanent>>();
        Set<UUID> claimed = new HashSet<UUID>();
        for (String[] pair : decision.pairs) {
            Permanent blocker = blockers.get(pair[0]);
            Permanent attacker = attackers.get(pair[1]);
            if (blocker == null || attacker == null || claimed.contains(blocker.getId())
                    || !blocker.canBlock(attacker.getId(), game)) {
                continue;
            }
            claimed.add(blocker.getId());
            List<Permanent> group = byAttacker.get(pair[1]);
            if (group == null) {
                group = new ArrayList<Permanent>();
                byAttacker.put(pair[1], group);
            }
            group.add(blocker);
        }

        List<UUID> used = new ArrayList<UUID>();
        List<String> summary = new ArrayList<String>();
        for (Map.Entry<String, List<Permanent>> entry : byAttacker.entrySet()) {
            Permanent attacker = attackers.get(entry.getKey());
            List<Permanent> group = entry.getValue();
            int required = Math.max(1, attacker.getMinBlockedBy());
            if (group.size() < required) {
                logger.warn("Kanna: model assigned " + group.size() + " blocker(s) to " + attacker.getName()
                        + ", which needs at least " + required
                        + " at once (menace or similar) -- dropping the whole group rather than risking"
                        + " an illegal block");
                if (metrics != null) {
                    metrics.recordInvalidToolCall();
                }
                continue;
            }
            // Mirror image of the min-blocked-by check above, but with no "no legal
            // configuration existed" escape hatch: CombatGroup.checkBlockRestrictions
            // treats an over-max group as unconditionally illegal (unlike the min case,
            // there is no equivalent of "if there aren't any possible blocker
            // configuration then it's legal due mtg rules"), so this alone drives
            // Combat.selectBlockers's retry loop up to 20 times, each retry re-firing
            // DECLARE_BLOCKERS_STEP_PRE and re-invoking the LLM. Trimming to the max
            // (rather than dropping the group outright, as the min case does) is
            // preferred here: a legal smaller block beats no block, and unlike the min
            // case there is always a legal subset to fall back to. getMaxBlockedBy() ==
            // 0 means "no maximum", not "zero allowed" -- that is the ordinary case for
            // almost every attacker and must not be misread as "block with nobody".
            int max = attacker.getMaxBlockedBy();
            if (max > 0 && group.size() > max) {
                logger.warn("Kanna: model assigned " + group.size() + " blocker(s) to " + attacker.getName()
                        + ", which allows at most " + max + " -- trimming to " + max
                        + " rather than risking an illegal block");
                if (metrics != null) {
                    metrics.recordInvalidToolCall();
                }
                group = group.subList(0, max);
            }
            for (Permanent blocker : group) {
                defendingPlayer.declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
                used.add(blocker.getId());
                summary.add(blocker.getName() + " blocks " + attacker.getName());
            }
        }

        // DARRELLBEST-FORK: every proposed group got dropped above (as opposed to the
        // model legitimately proposing an empty blocks array, i.e. "block with
        // nobody") is a decision the model failed to make usably, not one it made -- and
        // this class's whole premise is that a model failure gets a real heuristic move,
        // never a silent no-op. Reporting "not handled" here (the same false-return
        // convention declareBlocksAgentically already uses for decision.fallback) lets
        // selectAttackers/selectBlockers's existing, single call to heuristicBlocks --
        // sitting OUTSIDE this try block -- run instead. Deliberately NOT calling
        // heuristicBlocks directly from here: an earlier round of this exact method had
        // a bug where the fallback could run twice (once from inside the try, once from
        // the catch/caller) on fresh state; this return-a-bool convention is what fixed
        // that, so it must not be re-introduced here for the max-group-dropped case.
        if (used.isEmpty() && !decision.pairs.isEmpty()) {
            logger.warn("Kanna: every proposed block group was rejected, deferring to heuristics");
            return false;
        }

        logger.info("Kanna blocks with " + used.size() + " creature(s) via " + ollamaModel);
        recordHistory(game, "block", summary.isEmpty() ? "no blocks" : join(summary));
        return true;
    }

    // -------------------------------------------------------- heuristic combat fallback

    /**
     * Real fallback for selectAttackers, not a no-op: for each available attacker, reuse
     * the same CombatEvaluator arithmetic that annotated the prompt to decide whether
     * attacking is worthwhile, and declare directly. Simple on purpose -- this is a
     * fallback, not the primary brain. See the double-fire warning above selectAttackers:
     * this must never re-fire DECLARE_ATTACKERS_STEP_PRE or re-check replaceEvent.
     */
    private void heuristicAttacks(Game game, UUID attackingPlayerId) {
        Player attackingPlayer = game.getPlayer(attackingPlayerId);
        if (attackingPlayer == null) {
            return;
        }
        List<UUID> declared = new ArrayList<UUID>();
        for (UUID defenderId : game.getOpponents(playerId, true)) {
            Player defender = game.getPlayer(defenderId);
            if (defender == null || !defender.isInGame()) {
                continue;
            }
            List<CreatureView> blockerViews = untappedCreaturesOf(defenderId, game);
            for (Permanent attacker : getAvailableAttackers(defenderId, game)) {
                if (declared.contains(attacker.getId())) {
                    continue;
                }
                CreatureView attackerView = CreatureView.from("h-atk", attacker, game);
                AttackOutcome outcome = CombatEvaluator.evaluateLikely(attackerView, blockerViews);
                // attack unless it is a pure loss: the attacker surviving, killing a
                // blocker (a trade), or simply being unblockable all make it worthwhile
                boolean worthwhile = !outcome.attackerDies || !outcome.blockersThatDie.isEmpty()
                        || outcome.unblocked;
                if (worthwhile) {
                    attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
                    declared.add(attacker.getId());
                }
            }
        }
        logger.info("Kanna (heuristic): attacks with " + declared.size() + " creature(s)");
    }

    /**
     * Real fallback for selectBlockers, not a no-op: block to survive when the incoming
     * damage would otherwise be lethal (chumping if nothing better is available),
     * otherwise block only when the block is favourable for the blocker. Simple on
     * purpose -- this is a fallback, not the primary brain. See the double-fire warning
     * above selectBlockers: this must never re-fire DECLARE_BLOCKERS_STEP_PRE or
     * re-check replaceEvent.
     */
    private void heuristicBlocks(Ability source, Game game, UUID defendingPlayerId) {
        Player defendingPlayer = game.getPlayer(defendingPlayerId);
        if (defendingPlayer == null) {
            return;
        }
        List<Permanent> attackers = new ArrayList<Permanent>();
        for (UUID attackerId : game.getCombat().getAttackers()) {
            if (!defendingPlayerId.equals(game.getCombat().getDefendingPlayerId(attackerId, game))) {
                continue;
            }
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker != null) {
                attackers.add(attacker);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }

        Player me = game.getPlayer(playerId);
        int myLife = me == null ? 20 : me.getLife();

        // biggest threats first, so a forced chump goes to whichever attacker matters most
        List<Permanent> byThreat = new ArrayList<Permanent>(attackers);
        Collections.sort(byThreat, new Comparator<Permanent>() {
            @Override
            public int compare(Permanent a, Permanent b) {
                return Integer.compare(b.getPower().getValue(), a.getPower().getValue());
            }
        });

        // Worst-case damage still to come from every attacker not yet decided in this
        // pass. Re-derived per attacker rather than fixed from the opening total: once an
        // earlier attacker is actually blocked, the damage it would have dealt is no
        // longer coming, and treating the original alpha-strike total as fixed for the
        // rest of the loop over-chumps every attacker after whichever one first made that
        // total lethal, even after blocking has already brought the real total below life.
        int pendingDamageIfUnblocked = 0;
        for (Permanent attacker : byThreat) {
            pendingDamageIfUnblocked += CombatEvaluator.evaluateUnblocked(CreatureView.from("h-atk", attacker, game)).damageThrough;
        }
        int committedDamage = 0;

        List<Permanent> available = new ArrayList<Permanent>(getAvailableBlockers(game));
        List<UUID> used = new ArrayList<UUID>();
        int blockCount = 0;

        for (Permanent attacker : byThreat) {
            CreatureView attackerView = CreatureView.from("h-atk", attacker, game);
            int unblockedDamage = CombatEvaluator.evaluateUnblocked(attackerView).damageThrough;
            boolean mustChumpToSurvive = committedDamage + pendingDamageIfUnblocked >= myLife;

            // A minimum-blockers restriction (menace's "can't be blocked except by two or
            // more creatures", set via CantBeBlockedByOneEffect -> Permanent.minBlockedBy)
            // requires that many legal blockers assigned AT ONCE, or the whole assignment
            // is illegal: PermanentImpl.canBlock does not check this, so it passes the
            // per-candidate check below, but CombatGroup.checkBlockRestrictions discards
            // it later and Combat.selectBlockers re-invokes selectBlockers (up to 20
            // times, re-firing DECLARE_BLOCKERS_STEP_PRE on every retry) trying to get a
            // legal one. One blocker on a menace attacker is not a wasted block, it is a
            // retry storm. If there are not enough legal, unused candidates to meet the
            // requirement, leave this attacker unblocked entirely -- always legal --
            // rather than risk an illegal assignment.
            int required = Math.max(1, attacker.getMinBlockedBy());
            List<Permanent> eligible = new ArrayList<Permanent>();
            for (Permanent candidate : available) {
                if (!used.contains(candidate.getId()) && candidate.canBlock(attacker.getId(), game)) {
                    eligible.add(candidate);
                }
            }

            int actualDamage = unblockedDamage;
            if (eligible.size() >= required) {
                // Search for a favourable assignment rather than always taking the first
                // `required` eligible candidates -- with required == 1 that meant only
                // ever considering the first-listed blocker, so which blocker got tried
                // (and therefore whether a block happened at all) was a function of
                // battlefield ordering rather than which one was actually good. Slides a
                // required-sized window across the eligible list; the first favourable
                // window wins outright (this is a fallback, not a search for the
                // optimum -- it does not keep looking for a better one after finding a
                // good one), and the first window overall is kept as the chump of last
                // resort if none turn out favourable.
                List<Permanent> favourableAssignment = null;
                AttackOutcome favourableOutcome = null;
                List<Permanent> chumpAssignment = null;
                AttackOutcome chumpOutcome = null;
                for (int i = 0; i + required <= eligible.size(); i++) {
                    List<Permanent> window = eligible.subList(i, i + required);
                    List<CreatureView> windowViews = new ArrayList<CreatureView>();
                    for (Permanent blocker : window) {
                        windowViews.add(CreatureView.from("h-blk", blocker, game));
                    }
                    AttackOutcome outcome = CombatEvaluator.evaluateBlockedBy(attackerView, windowViews);
                    boolean anyAssignedDies = false;
                    for (Permanent blocker : window) {
                        if (outcome.blockersThatDie.contains(blocker.getName())) {
                            anyAssignedDies = true;
                            break;
                        }
                    }
                    boolean favourable = !anyAssignedDies || outcome.attackerDies;
                    if (favourable) {
                        favourableAssignment = new ArrayList<Permanent>(window);
                        favourableOutcome = outcome;
                        break;
                    }
                    if (chumpAssignment == null) {
                        chumpAssignment = new ArrayList<Permanent>(window);
                        chumpOutcome = outcome;
                    }
                }

                List<Permanent> assignment = favourableAssignment != null ? favourableAssignment
                        : (mustChumpToSurvive ? chumpAssignment : null);
                AttackOutcome chosenOutcome = favourableAssignment != null ? favourableOutcome : chumpOutcome;

                if (assignment != null) {
                    for (Permanent blocker : assignment) {
                        defendingPlayer.declareBlocker(defendingPlayerId, blocker.getId(), attacker.getId(), game, false);
                        used.add(blocker.getId());
                    }
                    blockCount += assignment.size();
                    actualDamage = chosenOutcome.damageThrough;
                }
            }

            committedDamage += actualDamage;
            pendingDamageIfUnblocked -= unblockedDamage;
        }
        logger.info("Kanna (heuristic): blocks with " + blockCount + " creature(s)");
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
