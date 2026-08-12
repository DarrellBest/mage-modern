package mage.player.ai.commander;

import mage.player.ai.ComputerPlayer;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.costs.mana.VariableManaCost;
import mage.abilities.effects.Effect;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.events.GameEvent;
import mage.game.match.MatchPlayer;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.score.GameStateEvaluator2;
import mage.players.Player;
import mage.players.net.UserData;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AI: helper class to simulate games with computer bot (each player replaced by simulated)
 *
 * @author BetaSteward_at_googlemail.com
 */
public final class SimulatedPlayer2 extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(SimulatedPlayer2.class);

    private static final boolean AI_SIMULATE_ALL_BAD_AND_GOOD_TARGETS = false; // TODO: enable and do performance test (it's increase calculations by x2, but is it useful?)

    /**
     * DARRELLBEST-FORK: how many times the same trigger may branch consecutively on one search
     * branch before the search stops fanning out on it. See {@link #exceededChainedTriggers}.
     * <p>
     * Matches {@code ComputerPlayer7.MAX_CHAINED_SAME_ACTIVATIONS}, which caps the equivalent loop
     * for activated abilities — but that cap is gated on {@code !game.isSimulation()} and so does
     * nothing inside the search. This is the search-side half of the same protection.
     */
    private static final int MAX_CHAINED_SAME_TRIGGERS = 3;

    /**
     * DARRELLBEST-FORK: how many times the same ACTION may be taken consecutively on one search
     * branch before the search stops offering it. Same idea and same number as
     * {@link #MAX_CHAINED_SAME_TRIGGERS}, applied to the option list rather than to triggers.
     * <p>
     * This is the search-side counterpart to {@code ComputerPlayer7.MAX_CHAINED_SAME_ACTIVATIONS},
     * which only fires when {@code !game.isSimulation()} and therefore never bounds the search.
     */
    private static final int MAX_CHAINED_SAME_ACTIONS = 3;

    /**
     * DARRELLBEST-FORK: how many target options to keep for a single ability, best-first.
     * <p>
     * See {@link #pruneToBestTargets}. Eight is deliberately generous relative to how many targets
     * a decision realistically turns on, while still bounding an ability whose legal target set is
     * the whole battlefield. Live stalls were observed at 48-96 permanents.
     */
    private static final int MAX_TARGET_OPTIONS_KEPT = 8;

    // warning, simulated player do not restore own data by game rollback
    private final boolean isSimulatedPlayer;
    private transient ConcurrentLinkedQueue<Ability> allActions; // all possible abilities to play (copies with already selected targets)
    private final Player originalPlayer; // copy of the original player, source of choices/results in tests

    public SimulatedPlayer2(Player originalPlayer, boolean isSimulatedPlayer) {
        super(originalPlayer.getId());
        this.originalPlayer = originalPlayer.copy();
        this.isSimulatedPlayer = isSimulatedPlayer;
        this.userData = UserData.getDefaultUserDataView();
        this.matchPlayer = new MatchPlayer(originalPlayer.getMatchPlayer(), this);
    }

    public SimulatedPlayer2(final SimulatedPlayer2 player) {
        super(player);
        this.isSimulatedPlayer = player.isSimulatedPlayer;
        // this.allActions = player.allActions; // dynamic, no need to copy
        this.originalPlayer = player.originalPlayer.copy();
    }

    @Override
    public void restore(Player player) {
        // simulated player can be created from any player type
        if (!originalPlayer.getClass().equals(player.getClass())) {
            throw new IllegalArgumentException("Wrong code usage: simulated player must use same player class all the time. Need "
                    + originalPlayer.getClass().getSimpleName() + ", but try to restore " + player.getClass().getSimpleName());
        }

        super.restore(player.getRealPlayer());
    }

    @Override
    public SimulatedPlayer2 copy() {
        return new SimulatedPlayer2(this);
    }

    /**
     * Find all playable abilities with all possible targets (targets already selected in ability)
     */
    public List<Ability> simulatePriority(Game game) {
        allActions = new ConcurrentLinkedQueue<>();
        Game sim = game.createSimulationForAI();
        simulateOptions(sim);

        // possible actions
        List<Ability> list = new ArrayList<>(allActions);
        Collections.reverse(list);

        // DARRELLBEST-FORK: drop any action this branch has already taken MAX_CHAINED_SAME_ACTIONS
        // times consecutively. ComputerPlayer7 caps chained activations only when
        // !game.isSimulation(), so in the SEARCH a free repeatable outlet (Altar of Dementia's
        // "sacrifice a creature" on a board that keeps making goblin tokens) stays available at
        // every level and the branching never bottoms out. Measured: Krenko commander mirrors
        // spent whole minutes re-simulating consecutive Altar activations.
        //
        // Filtered BEFORE Pass is appended, so passing is always still an option and the list can
        // never come back empty.
        list.removeIf(action -> exceededChainedActions(action, game, MAX_CHAINED_SAME_ACTIONS));

        // DARRELLBEST-FORK: collapse INTERCHANGEABLE actions to one branch.
        //
        // With twenty identical Goblin tokens on the battlefield, "sacrifice token #1" through
        // "#20" are twenty separate branches whose resulting game states are identical. The search
        // explored all of them, at every level -- so the real cost was not 20x but 20^depth. On the
        // token boards this bot actually plays (286 Krenko activations in one six-game bench run,
        // a single attack of 24 creatures) that is the dominant source of branching.
        //
        // Note the key uses the source's NAME and stats, NOT its id -- deliberately the opposite of
        // the chained-action cap above, which uses the id precisely so two different Mountains are
        // NOT treated as the same thing. Different questions: that one asks "am I repeating myself?"
        // and must distinguish permanents; this one asks "are these choices interchangeable?" and
        // must collapse them. Power/toughness and marked damage are in the key so a damaged token is
        // not folded together with a healthy one.
        list = dedupeInterchangeable(list, game);

        // pass action
        list.add(new PassAbility());

        if (logger.isTraceEnabled()) {
            for (Ability a : allActions) {
                logger.info("ability==" + a);
                if (!a.getTargets().isEmpty()) {
                    MageObject mageObject = game.getObject(a.getFirstTarget());
                    if (mageObject != null) {
                        logger.info("   target=" + mageObject.getName());
                    } else {
                        Player player = game.getPlayer(a.getFirstTarget());
                        if (player != null) {
                            logger.info("   target=" + player.getName());
                        }
                    }
                }
            }
        }

        return list;
    }

    // DARRELLBEST-FORK (keep on merge/rebase from upstream): each ability's own option
    // combinations are already capped individually (see MAX_TARGET_OPTIONS_PER_ABILITY_FORK_FIX
    // in PlayerImpl), but nothing capped the TOTAL across every playable ability on the whole
    // battlefield -- with enough activatable sources (big board, myriad-style triggers, etc.)
    // that aggregate could still explode and freeze/OOM the AI. This is the backstop.
    //
    // Was 3000 -- still not tight enough. This is the branching factor at EVERY one of maxDepth
    // (== skill, 6-8 for a "hard" AI) levels of minimax search, so the real cost is this number
    // raised to that power, not the number itself. Confirmed live: 3000 still let the server peg
    // 18 of 24 CPU cores continuously for 4+ minutes straight and go completely unresponsive to
    // every client on a 50-permanent board. 200 keeps normal decisions (which rarely approach
    // even a few dozen real options) essentially untouched while making the worst case bounded.
    private static final int MAX_TOTAL_ACTIONS_FORK_FIX = 200;

    private void simulateOptions(Game game) {
        List<ActivatedAbility> playables = game.getPlayer(playerId).getPlayable(game, isSimulatedPlayer);
        for (ActivatedAbility ability : playables) {
            if (ability.isManaAbility()) {
                continue;
            }
            if (allActions.size() >= MAX_TOTAL_ACTIONS_FORK_FIX) {
                break;
            }
            List<Ability> options = game.getPlayer(playerId).getPlayableOptions(ability, game);
            options = optimizeOptions(game, options, ability);
            if (options.isEmpty()) {
                allActions.add(ability);
            } else {
                for (Ability option : options) {
                    allActions.add(option);
                }
            }
        }
    }

    @Override
    protected void addVariableXOptions(List<Ability> options, Ability ability, int targetNum, Game game) {
        // calculate the mana that can be used for the x part
        int numAvailable = getAvailableManaProducers(game).size() - ability.getManaCosts().manaValue();

        if (numAvailable > 0) {
            // check if variable mana costs is included and get the multiplier
            VariableManaCost variableManaCost = null;
            for (ManaCost cost : ability.getManaCostsToPay()) {
                if (cost instanceof VariableManaCost && !cost.isPaid()) {
                    variableManaCost = (VariableManaCost) cost;
                    break; // only one VariableManCost per spell (or is it possible to have more?)
                }
            }
            if (variableManaCost != null) {
                int xInstancesCount = variableManaCost.getXInstancesCount();

                for (int mana = variableManaCost.getMinX(); mana <= numAvailable; mana++) {
                    if (mana % xInstancesCount == 0) { // use only values dependant from multiplier
                        // find possible X value to pay
                        int xAnnounceValue = mana / xInstancesCount;
                        Ability newAbility = ability.copy();
                        VariableManaCost varCost = null;
                        for (ManaCost cost : newAbility.getManaCostsToPay()) {
                            if (cost instanceof VariableManaCost && !cost.isPaid()) {
                                varCost = (VariableManaCost) cost;
                                break; // only one VariableManCost per spell (or is it possible to have more?)
                            }
                        }
                        // find real X value after replace events
                        newAbility.addManaCostsToPay(new ManaCostsImpl<>(new StringBuilder("{").append(xAnnounceValue).append('}').toString()));
                        newAbility.getManaCostsToPay().setX(xAnnounceValue, xAnnounceValue * xInstancesCount);
                        if (varCost != null) {
                            varCost.setPaid();
                        }
                        newAbility.adjustTargets(game);
                        // add the different possible target option for the specific X value
                        if (newAbility.getTargets().getNextUnchosen(game) != null) {
                            addTargetOptions(options, newAbility, targetNum, game);
                        }
                    }

                }
            }

        }
    }

    protected List<Ability> optimizeOptions(Game game, List<Ability> options, Ability ability) {
        if (options.isEmpty()) {
            return options;
        }

        // remove invalid targets
        // TODO: is it useless cause it already filtered before?
        options.removeIf(option -> !option.getTargets().isChosen(game));

        if (AI_SIMULATE_ALL_BAD_AND_GOOD_TARGETS) {
            return options;
        }

        // determine if all effects are bad or good
        Iterator<Ability> iterator = options.iterator();
        boolean bad = true;
        boolean good = true;

        // TODO: add custom outcome from ability?
        for (Effect effect : ability.getEffects()) {
            if (effect.getOutcome().isGood()) {
                bad = false;
            } else {
                good = false;
            }
        }

        if (bad) {
            // remove its own creatures, player itself for bad effects with one target
            while (iterator.hasNext()) {
                Ability ability1 = iterator.next();
                if (ability1.getTargets().size() == 1 && ability1.getTargets().get(0).getTargets().size() == 1) {
                    Permanent permanent = game.getPermanent(ability1.getFirstTarget());
                    if (permanent != null && !game.getOpponents(playerId, true).contains(permanent.getControllerId())) {
                        iterator.remove();
                        continue;
                    }
                    if (ability1.getFirstTarget().equals(playerId)) {
                        iterator.remove();
                    }
                }
            }
        }
        if (good) {
            // remove opponent creatures and opponent for only good effects with one target
            while (iterator.hasNext()) {
                Ability ability1 = iterator.next();
                if (ability1.getTargets().size() == 1 && ability1.getTargets().get(0).getTargets().size() == 1) {
                    Permanent permanent = game.getPermanent(ability1.getFirstTarget());
                    if (permanent != null && game.getOpponents(playerId, true).contains(permanent.getControllerId())) {
                        iterator.remove();
                        continue;
                    }
                    if (game.getOpponents(playerId, true).contains(ability1.getFirstTarget())) {
                        iterator.remove();
                    }
                }
            }
        }

        return pruneToBestTargets(game, options, bad);
    }

    /**
     * DARRELLBEST-FORK: keep only the most VALUABLE target options for an ability, discarding the
     * rest, rather than handing the search every legal combination.
     * <p>
     * Diagnosed from the live server. On large boards the search would emit
     * {@code "unknown use case (too many possible targets?)"} — which
     * {@link ComputerPlayer6#printFreezeNode} only prints when the root node has NO CHILDREN, i.e.
     * the think-time budget was spent before a single node was expanded. So the cost was in
     * ENUMERATING options, not in searching them. Observed across six live games at battlefield
     * sizes of 23 to 96 permanents, most stalls at 48+.
     * <p>
     * The existing caps do not prevent this. {@code MAX_TOTAL_ACTIONS_FORK_FIX} is checked only
     * BETWEEN abilities, so one ability with a large target space blows the whole budget before the
     * cap is consulted; and the good/bad filtering above removes illegal-ish targets without ever
     * bounding how many remain.
     * <p>
     * Targeting a 7/7 is not 40 times more interesting than targeting each of 40 tokens. Ranking by
     * the evaluator's own permanent score and keeping the top few loses almost nothing in decision
     * quality while making enumeration bounded — the search gets a short list of the choices that
     * actually matter and can then afford to explore them properly.
     * <p>
     * Ranking uses {@link CommanderEvalParams#DEFAULT} rather than the player's tuned weights: this
     * class extends {@code ComputerPlayer} and holds no params, and this is an ordering heuristic
     * for which options to look at, not the evaluation the search ultimately scores with.
     */
    private List<Ability> pruneToBestTargets(Game game, List<Ability> options, boolean badEffect) {
        if (options.size() <= MAX_TARGET_OPTIONS_KEPT) {
            return options;
        }
        List<Ability> ranked = new ArrayList<>(options);
        ranked.sort((a, b) -> Integer.compare(
                targetValue(game, b, badEffect), targetValue(game, a, badEffect)));
        if (logger.isDebugEnabled()) {
            logger.debug("simulating -- pruned target options " + options.size()
                    + " -> " + MAX_TARGET_OPTIONS_KEPT);
        }
        return new ArrayList<>(ranked.subList(0, MAX_TARGET_OPTIONS_KEPT));
    }

    /**
     * How much this option's targets are worth to us. For a detrimental effect the best target is
     * the opponent's most valuable permanent; for a beneficial one it is our own. Players as targets
     * score 0, so a permanent is always preferred over a face hit when both are legal.
     */
    private int targetValue(Game game, Ability option, boolean badEffect) {
        int total = 0;
        for (Target target : option.getTargets()) {
            for (UUID id : target.getTargets()) {
                Permanent permanent = game.getPermanent(id);
                if (permanent == null) {
                    continue;
                }
                int worth = GameStateEvaluator2.evaluatePermanent(
                        permanent, game, true, CommanderEvalParams.DEFAULT);
                boolean theirs = !permanent.getControllerId().equals(playerId);
                total += (theirs == badEffect) ? worth : -worth;
            }
        }
        return total;
    }

    public List<Combat> addAttackers(Game game) {
        Map<Integer, Combat> engagements = new HashMap<>();
        //useful only for two player games - will only attack first opponent
        UUID defenderId = game.getOpponents(playerId, true).iterator().next();
        List<Permanent> attackersList = super.getAvailableAttackers(defenderId, game);
        //use binary digits to calculate powerset of attackers
        int powerElements = (int) Math.pow(2, attackersList.size());
        StringBuilder binary = new StringBuilder();
        for (int i = powerElements - 1; i >= 0; i--) {
            Game sim = game.createSimulationForAI();
            binary.setLength(0);
            binary.append(Integer.toBinaryString(i));
            while (binary.length() < attackersList.size()) {
                binary.insert(0, '0');
            }
            for (int j = 0; j < attackersList.size(); j++) {
                if (binary.charAt(j) == '1') {
                    setStoredBookmark(sim.bookmarkState()); // makes it possible to UNDO a declared attacker with costs from e.g. Propaganda
                    if (!sim.getCombat().declareAttacker(attackersList.get(j).getId(), defenderId, playerId, sim)) {
                        sim.undo(playerId);
                    }
                }
            }
            if (engagements.put(sim.getCombat().getValue().hashCode(), sim.getCombat()) != null) {
                logger.debug("simulating -- found redundant attack combination");
            } else {
                logger.debug("simulating -- attack:" + sim.getCombat().getGroups().size());
            }
        }
        List list = new ArrayList<>(engagements.values());
        Collections.sort(list, new Comparator<Combat>() {
            @Override
            public int compare(Combat o1, Combat o2) {
                return Integer.valueOf(o2.getGroups().size()).compareTo(o1.getGroups().size());
            }
        });
        return list;
    }

    public List<Combat> addBlockers(Game game) {
        Map<Integer, Combat> engagements = new HashMap<>();
        int numGroups = game.getCombat().getGroups().size();
        if (numGroups == 0) {
            return Collections.emptyList();
        }

        //add a node with no blockers
        Game sim = game.createSimulationForAI();
        engagements.put(sim.getCombat().getValue().hashCode(), sim.getCombat());
        sim.fireEvent(GameEvent.getEvent(GameEvent.EventType.DECLARED_BLOCKERS, playerId, playerId));

        List<Permanent> blockers = getAvailableBlockers(game);
        addBlocker(game, blockers, engagements);

        return new ArrayList<>(engagements.values());
    }

    protected void addBlocker(Game game, List<Permanent> blockers, Map<Integer, Combat> engagements) {
        if (blockers.isEmpty()) {
            return;
        }
        int numGroups = game.getCombat().getGroups().size();
        //try to block each attacker with each potential blocker
        Permanent blocker = blockers.get(0);
        logger.debug("simulating -- block:" + blocker);
        List<Permanent> remaining = remove(blockers, blocker);
        for (int i = 0; i < numGroups; i++) {
            if (game.getCombat().getGroups().get(i).canBlock(blocker, game)) {
                Game sim = game.createSimulationForAI();
                sim.getCombat().getGroups().get(i).addBlocker(blocker.getId(), playerId, sim);
                if (engagements.put(sim.getCombat().getValue().hashCode(), sim.getCombat()) != null) {
                    logger.debug("simulating -- found redundant block combination");
                }
                addBlocker(sim, remaining, engagements);  // and recurse minus the used blocker
            }
        }
        addBlocker(game, remaining, engagements);
    }

    @Override
    public boolean triggerAbility(TriggeredAbility source, Game game) {
        Ability ability = source.copy();
        List<Ability> options = getPlayableOptions(ability, game);
        if (options.isEmpty() || exceededChainedTriggers(ability, game)) {
            // no options (or the same trigger has already branched MAX_CHAINED_SAME_TRIGGERS times
            // consecutively on this branch) - activate as is, without fanning out children
            logger.debug("simulating -- triggered ability:" + ability);
            game.getStack().push(game, new StackAbility(ability, playerId));
            if (ability.activate(game, false) && ability.isUsesStack()) {
                game.fireEvent(new GameEvent(GameEvent.EventType.TRIGGERED_ABILITY, ability.getId(), ability, ability.getControllerId()));
            }
            game.applyEffects();
            game.getPlayers().resetPassed();
        } else {
            // many options - activate and add to sims tree
            // TODO: AI run all sims, but do not use best option for triggers yet
            SimulationNode2 parent = (SimulationNode2) game.getCustomData();
            int depth = parent.getDepth() - 1;
            if (depth == 0) {
                return true;
            }
            logger.debug("simulating -- triggered ability - adding children:" + options.size());
            for (Ability option : options) {
                addAbilityNode(parent, option, depth, game);
            }
        }
        return true;
    }

    /** Collapse actions whose outcomes are interchangeable, keeping the first of each kind. */
    private List<Ability> dedupeInterchangeable(List<Ability> actions, Game game) {
        Set<String> seen = new HashSet<>();
        List<Ability> kept = new ArrayList<>(actions.size());
        for (Ability action : actions) {
            if (seen.add(interchangeableKey(action, game))) {
                kept.add(action);
            }
        }
        if (logger.isDebugEnabled() && kept.size() < actions.size()) {
            logger.debug("simulating -- collapsed interchangeable actions " + actions.size()
                    + " -> " + kept.size());
        }
        return kept;
    }

    private String interchangeableKey(Ability action, Game game) {
        StringBuilder key = new StringBuilder();
        MageObject source = action.getSourceObject(game);
        if (source instanceof Permanent) {
            Permanent p = (Permanent) source;
            key.append(p.getName())
                    .append('/').append(p.getPower().getValue())
                    .append('/').append(p.getToughness().getValue())
                    .append('/').append(p.getDamage())
                    .append('/').append(p.isTapped());
        } else {
            key.append(source == null ? "?" : source.getName());
        }
        key.append('|').append(action.getRule());
        for (Target target : action.getTargets()) {
            List<String> ids = new ArrayList<>();
            for (UUID id : target.getTargets()) {
                ids.add(String.valueOf(id));
            }
            Collections.sort(ids);
            key.append('|').append(String.join(",", ids));
        }
        return key.toString();
    }

    /**
     * DARRELLBEST-FORK: bounds search explosion caused by a trigger that keeps re-triggering itself.
     * <p>
     * Unlike an activated ability, a trigger is <b>mandatory</b> — the AI cannot decline one without
     * simulating an illegal game state. So this does not refuse the trigger. It stops the search from
     * <i>branching</i> on it: past the cap the trigger is pushed and resolved as-is, taking the same
     * path used when it has no options at all. The simulated game stays legal; only the fan-out that
     * makes the search unbounded is removed.
     * <p>
     * <b>The count comes from the node's ancestry, not from a field on this player.</b> Search
     * branches interleave through a single {@code SimulatedPlayer2}, so an instance counter would
     * count sibling branches against each other and trip on breadth rather than on repetition.
     * Walking parents counts only what actually happened on the path leading to this node.
     * <p>
     * Identity is (source name + rule text) rather than object identity, for the same reason
     * {@code ComputerPlayer7.signatureOf} uses it: the search works on copies of the game, so the
     * same printed ability is a different instance at every level and comparing instances would
     * never match.
     */
    private boolean exceededChainedTriggers(Ability ability, Game game) {
        return exceededChainedActions(ability, game, MAX_CHAINED_SAME_TRIGGERS);
    }

    /**
     * @return true when {@code ability} already appears {@code max} times consecutively in this
     *         search branch's ancestry, i.e. the branch is repeating itself rather than progressing
     */
    private boolean exceededChainedActions(Ability ability, Game game, int max) {
        if (!(game.getCustomData() instanceof SimulationNode2)) {
            return false;
        }
        String signature = signatureOf(ability, game);
        if (signature == null) {
            return false; // unresolvable source: never cap, rather than bucket it with every other one
        }
        int chained = 0;
        for (SimulationNode2 node = (SimulationNode2) game.getCustomData();
                node != null; node = node.getParent()) {
            if (!nodeMatchesSignature(node, signature)) {
                break; // consecutive only -- anything else on the path resets the chain
            }
            chained++;
            if (chained >= max) {
                // debug, not warn: a search that trips this trips it many times over, and the point
                // is to bound the search rather than to flood the log the loop is already flooding
                logger.debug("simulating -- capped a chained action (" + chained
                        + "x consecutively on this branch): " + signature);
                return true;
            }
        }
        return false;
    }

    private boolean nodeMatchesSignature(SimulationNode2 node, String signature) {
        List<Ability> abilities = node.getAbilities();
        Game nodeGame = node.getGame();
        if (abilities == null || abilities.isEmpty() || nodeGame == null) {
            // no ability recorded on this node, or no game to resolve its source against: treat as
            // "not the same trigger" so an unknown node breaks the chain rather than extending it
            return false;
        }
        for (Ability nodeAbility : abilities) {
            if (signature.equals(signatureOf(nodeAbility, nodeGame))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Identity is (source PERMANENT id + rule text) — see {@code ComputerPlayer7.signatureOf} for
     * the full reasoning. Briefly: not the Ability instance, because game copies make instances
     * differ; and not the source NAME, because every Mountain shares a name and rule, which
     * collapsed N distinct permanents into one signature and made the cap fire on the fourth land
     * as if it were the same land four times. A null source id yields no signature and is never
     * capped, rather than sharing a degenerate bucket with every other unresolvable ability.
     */
    private String signatureOf(Ability ability, Game game) {
        return ability.getSourceId() == null ? null : ability.getSourceId() + "|" + ability.getRule();
    }

    protected void addAbilityNode(SimulationNode2 parent, Ability ability, int depth, Game game) {
        Game sim = game.createSimulationForAI();
        sim.getStack().push(sim, new StackAbility(ability, playerId));
        if (ability.activate(sim, false) && ability.isUsesStack()) {
            sim.fireEvent(new GameEvent(GameEvent.EventType.TRIGGERED_ABILITY, ability.getId(), ability, ability.getControllerId()));
        }
        sim.applyEffects();
        SimulationNode2 newNode = new SimulationNode2(parent, sim, depth, playerId);
        logger.debug("simulating -- node #:" + SimulationNode2.getCount() + " triggered ability option");
        for (Target target : ability.getTargets()) {
            for (UUID targetId : target.getTargets()) {
                newNode.getTargets().add(targetId); // save for info only (real targets in newNode.game.stack already)
            }
        }
        parent.children.add(newNode);
    }

    @Override
    public boolean priority(Game game) {
        // simulated player do nothing - it must pass until stack resolve to see final game score after action apply

        // it's a workaround for Karn Liberated restart ability (see CommandersGameRestartTest)
        // reason: restarted game is broken (miss clear code of some game/player data?) and ai can't simulate it
        // so game is freezes on non empty stack (last part of karn's restart ability)
        if (game.getStack().isEmpty()) {
            game.pause();
        }
        pass(game);
        return false;
    }

    @Override
    public boolean flipCoinResult(Game game) {
        // same random results set up support in AI tests, see TestComputerPlayer for docs
        return originalPlayer.flipCoinResult(game);
    }

    @Override
    public int rollDieResult(int sides, Game game) {
        // same random results set up support in AI tests, see TestComputerPlayer for docs
        return originalPlayer.rollDieResult(sides, game);
    }
}
