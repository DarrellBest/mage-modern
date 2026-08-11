package mage.player.ai.commander;

import mage.abilities.Ability;
import mage.constants.RangeOfInfluence;
import mage.MageObject;
import mage.abilities.ActivatedAbility;
import mage.game.Game;
import mage.player.ai.commander.score.GameStateEvaluator2;
import org.apache.log4j.Logger;

import java.util.Date;
import java.util.LinkedList;

/**
 * AI: server side bot with game simulations (mad bot, the latest version)
 *
 * @author ayratn
 */
public class ComputerPlayer7 extends ComputerPlayer6 {

    private static final Logger logger = Logger.getLogger(ComputerPlayer7.class);

    private boolean allowBadMoves;

    public ComputerPlayer7(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public ComputerPlayer7(final ComputerPlayer7 player) {
        super(player);
        this.allowBadMoves = player.allowBadMoves;
    }

    @Override
    public ComputerPlayer7 copy() {
        return new ComputerPlayer7(this);
    }

    /**
     * DARRELLBEST-FORK: cap on how many times this player may take priority within one turn step
     * before it is forced to pass.
     * <p>
     * Diagnosed from a benchmark game that ran 17+ minutes without finishing while writing 57MB of
     * log. The AI was not deadlocked, it was churning: activate Krenko's Altar of Dementia (a free
     * sacrifice outlet), which changes the game state, which makes {@code getNextAction} return
     * false with "need re-calculation (game state changed between actions)", which re-runs the whole
     * search, which activates it again. 1,095 Altar activations in a 20k-line window. Krenko keeps
     * making goblin tokens, so the supply of creatures to sacrifice never runs out and the loop has
     * no natural end.
     * <p>
     * {@code maxNodes} does not help: it bounds one search, not the number of times the search
     * restarts. Neither does {@code maxThinkTimeSecs}, which is also per search. This is the only
     * level at which the loop is visible.
     * <p>
     * The number is deliberately generous. A real turn with a long chain of activations (a storm
     * turn, an aristocrats sacrifice loop played to a genuine win) should complete untouched; 500
     * priorities in a single step is far past any honest line and reachable only by a bot that is
     * not making progress. When it trips, the bot passes rather than continuing, so it loses the
     * rest of that step instead of the whole game.
     */
    private static final int MAX_PRIORITIES_PER_STEP = 500;

    /**
     * DARRELLBEST-FORK: how many times one ability may be activated in a row before this player
     * stops considering it.
     * <p>
     * The priority budget above is a blunt backstop that fires after 500 wasted decisions. This is
     * the targeted fix for what actually causes those 500: a repeatable ability with no real cost
     * (Altar of Dementia's "sacrifice a creature", Goblin Bombardment, an Umbral Mantle untap loop)
     * that the search re-derives as attractive every time the board changes -- which it just did,
     * because the ability itself changed it. Observed at 1,095 consecutive activations of a single
     * Altar of Dementia in one game.
     * <p>
     * Three is enough for every legitimate repeated activation this is likely to interrupt: pumping
     * a creature a few times, milling for a specific value, activating a mana ability chain. A line
     * that genuinely needs the same ability a fourth consecutive time -- a real combo kill -- is
     * given up, and that is the deliberate trade: this bot could not execute such a line reliably
     * anyway (it is a depth-4 search), and the alternative is a game that never ends.
     * <p>
     * The counter is CONSECUTIVE, not per-turn: activating something else resets it, so a genuine
     * back-and-forth sequence is unaffected and only a stuck repetition trips it.
     */
    private static final int MAX_CHAINED_SAME_ACTIVATIONS = 3;

    private int prioritiesThisStep;
    private int lastPriorityTurn = -1;
    private String lastPriorityStep = "";

    private String lastActivatedSignature = "";
    private int chainedSameActivations;

    @Override
    public boolean priority(Game game) {
        if (exceededPriorityBudget(game)) {
            pass(game);
            return false;
        }
        game.resumeTimer(getTurnControlledBy());
        boolean result = priorityPlay(game);
        game.pauseTimer(getTurnControlledBy());
        return result;
    }

    /**
     * DARRELLBEST-FORK: refuses an ability that has already been activated
     * {@link #MAX_CHAINED_SAME_ACTIVATIONS} times consecutively.
     * <p>
     * This is the single choke point through which the AI actually takes an action, so it catches
     * the loop wherever the search decided on it, rather than trying to prune the many places a
     * candidate action is generated.
     * <p>
     * Identity is (source object name + rule text), not the ability's object identity: the search
     * works on COPIES of the game, so the ability instance handed here differs run to run even when
     * it is the same printed ability on the same permanent. Comparing instances would never match
     * and the cap would never fire.
     */
    @Override
    public boolean activateAbility(ActivatedAbility ability, Game game) {
        if (!game.isSimulation()) {
            String signature = signatureOf(ability, game);
            if (signature.equals(lastActivatedSignature)) {
                chainedSameActivations++;
                if (chainedSameActivations > MAX_CHAINED_SAME_ACTIVATIONS) {
                    logger.warn("AI refused a chained activation (" + chainedSameActivations
                            + "x in a row) to break a no-progress loop: " + signature
                            + " - player: " + getName());
                    return false;
                }
            } else {
                lastActivatedSignature = signature;
                chainedSameActivations = 1;
            }
        }
        return super.activateAbility(ability, game);
    }

    private String signatureOf(ActivatedAbility ability, Game game) {
        MageObject source = ability.getSourceObject(game);
        String name = source == null ? "?" : source.getName();
        return name + "|" + ability.getRule();
    }

    /**
     * @return true when this player has already taken priority {@link #MAX_PRIORITIES_PER_STEP}
     *         times in the current turn step, which means it is looping rather than progressing
     */
    private boolean exceededPriorityBudget(Game game) {
        String step = String.valueOf(game.getTurnStepType());
        int turn = game.getTurnNum();
        if (turn != lastPriorityTurn || !step.equals(lastPriorityStep)) {
            lastPriorityTurn = turn;
            lastPriorityStep = step;
            prioritiesThisStep = 0;
        }
        prioritiesThisStep++;
        if (prioritiesThisStep == MAX_PRIORITIES_PER_STEP + 1) {
            // Log once, not on every subsequent priority: a loop that trips this is by definition
            // going to trip it many more times, and the point is to notice it, not to flood the log
            // that the loop is already flooding.
            logger.warn("AI priority budget exhausted (" + MAX_PRIORITIES_PER_STEP + " in "
                    + step + " of turn " + turn + ") - passing to break a no-progress loop."
                    + " Player: " + getName());
        }
        return prioritiesThisStep > MAX_PRIORITIES_PER_STEP;
    }

    private boolean priorityPlay(Game game) {
        game.getState().setPriorityPlayerId(playerId);
        game.firePriorityEvent(playerId);
        switch (game.getTurnStepType()) {
            case UPKEEP:
            case DRAW:
                pass(game);
                return false;
            case PRECOMBAT_MAIN:
                // 09.03.2020:
                // in old version it passes opponent's pre-combat step (game.isActivePlayer(playerId) -> pass(game))
                // why?!
                printBattlefieldScore(game, "Sim PRIORITY on MAIN 1");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case BEGIN_COMBAT:
                pass(game);
                return false;
            case DECLARE_ATTACKERS:
                printBattlefieldScore(game, "Sim PRIORITY on DECLARE ATTACKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case DECLARE_BLOCKERS:
                printBattlefieldScore(game, "Sim PRIORITY on DECLARE BLOCKERS");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
            case END_COMBAT:
                pass(game);
                return false;
            case POSTCOMBAT_MAIN:
                printBattlefieldScore(game, "Sim PRIORITY on MAIN 2");
                if (actions.isEmpty()) {
                    calculateActions(game);
                } else {
                    // TODO: is it possible non empty actions without calculation?!
                    throw new IllegalStateException("wtf");
                }
                act(game);
                return true;
            case END_TURN:
            case CLEANUP:
                actionCache.clear();
                pass(game);
                return false;
        }
        return false;
    }

    protected void calculateActions(Game game) {
        if (!getNextAction(game)) {
            currentScore = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            Game sim = createSimulation(game);
            SimulationNode2.resetCount();
            root = new SimulationNode2(null, sim, maxDepth, playerId);
            addActionsTimed(); // TODO: root can be null again after addActionsTimed O_o need to research (it's a CPU AI problem?)
            if (root != null && root.children != null && !root.children.isEmpty()) {
                logger.trace("After add actions timed: root.children.size = " + root.children.size());
                root = root.children.get(0);

                // prevent repeating always the same action with no cost
                boolean doThis = true;
                if (root.abilities.size() == 1) {
                    for (Ability ability : root.abilities) {
                        if (ability.getManaCosts().manaValue() == 0
                                && ability.getCosts().isEmpty()) {
                            if (actionCache.contains(ability.getRule() + '_' + ability.getSourceId())) {
                                doThis = false; // don't do it again
                            }
                        }
                    }
                }

                if (doThis) {
                    actions = new LinkedList<>(root.abilities);
                    combat = root.combat; // TODO: must use copy?!
                    for (Ability ability : actions) {
                        actionCache.add(ability.getRule() + '_' + ability.getSourceId());
                    }
                }
            } else {
                // nothing to choose or freeze/infinite game
                logger.info("AI player can't find next action: " + getName());
            }
        } else {
            logger.debug("Next Action exists!");
        }
    }

    @Override
    public void setAllowBadMoves(boolean allowBadMoves) {
        this.allowBadMoves = allowBadMoves;
    }
}
