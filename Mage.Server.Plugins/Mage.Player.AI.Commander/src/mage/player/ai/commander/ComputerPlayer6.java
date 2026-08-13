package mage.player.ai.commander;

import mage.player.ai.CombatEvaluator;
import mage.player.ai.ComputerPlayer;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.Modes;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.StaticAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.SearchEffect;
import mage.abilities.keyword.*;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.combat.CombatGroup;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.ai.commander.optimizers.TreeOptimizer;
import mage.player.ai.commander.optimizers.impl.*;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.score.GameStateEvaluator2;
import mage.player.ai.commander.util.CombatInfo;
import mage.player.ai.commander.util.CombatUtil;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.target.common.TargetCardInHand;
import mage.util.CardUtil;
import mage.util.RandomUtil;
import mage.util.ThreadUtils;
import mage.util.XmageThreadFactory;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * AI: server side bot with game simulations (mad bot, part of implementation)
 *
 * @author nantuko, JayDi85
 */
public class ComputerPlayer6 extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(ComputerPlayer6.class);

    /**
     * DARRELLBEST-FORK: audit trail of what the bot actually PLAYS, one compact line per action.
     * <p>
     * Deliberately named OUTSIDE the {@code mage.player.ai} tree. The server ships
     * {@code log4j.logger.mage.player.ai=warn} inside mage-server.jar, which silences this class's
     * INFO -- including the "SELECTED ACTION" line -- so a 198MB production log contained ZERO
     * record of any decision the AI made, only that it had been slow.
     * <p>
     * Turning the whole package back to INFO is not the answer: it emits roughly 28MB per game of
     * search internals. This logger inherits {@code rootLogger=info} instead, so it is written with
     * no config change, and one line per action is a rounding error by comparison. Silence it with
     * {@code log4j.logger.mage.ai.play=warn} if it is ever unwanted.
     */
    private static final Logger playLog = Logger.getLogger("mage.ai.play");

    // TODO: add and research maxNodes logs, is it good to increase from 5000 to 50000 for better results?
    // TODO: increase maxNodes due AI skill level like max depth?
    // DARRELLBEST-FORK (keep on merge/rebase from upstream): lowered from 5000 to 1500.
    // Observed on the live production server: MAD bots in a 4-player Commander game timed
    // out on every decision -- turns advanced at exactly 54s, with a timeout warning every
    // 6.0s and no game actions logged in between. At skill 2, maxThinkTimeSecs = skill * 3
    // = 6s (the observed cadence) and maxDepth is already floored at 4 (see the
    // "skill < 4" branch above -- lowering skill further cannot raise it), so neither knob
    // was available to fix this. That leaves node count as the only remaining lever: with
    // 5000, search ran out the clock (maxThinkTimeSecs) before ever reaching
    // MAX_SIMULATED_NODES_PER_CALC, so every decision was an abandoned mid-search read
    // rather than a completed one. Cutting the node cap makes the search far more likely
    // to terminate on node count before the clock, trading search breadth for actually
    // returning a real (if shallower) answer instead of a timed-out one. 1500 is a
    // starting point, not a validated optimum -- see the cp7-vs-cp7 bench numbers in
    // commander-bench-report.md for the one measurement taken so far; retune by
    // measurement if turn times or play quality still look wrong.
    private static final int MAX_SIMULATED_NODES_PER_CALC = 1500;
    private static final int MAX_SIMULATED_NODES_PER_ERROR = 5100; // TODO: debug only, set low value to find big calculations

    // DARRELLBEST-FORK: MAD reads ComputerPlayer.COMPUTER_MAX_THREADS_FOR_SIMULATIONS, which is
    // package-private and was only reachable because MAD sits in the mage.player.ai package itself.
    // This fork lives in its own package, so it declares its own copy rather than widening the
    // shared field's visibility -- an edit to shared code would touch every bot on the live server,
    // which is exactly what forking was meant to avoid. Owning the value here is also the point:
    // this bot's pool sizing is a knob to tune independently of MAD's. Same value (5) as upstream,
    // so behaviour starts identical to MAD and any divergence from here is deliberate.
    private static final int SIMULATION_THREADS = 5;

    // same params as Executors.newFixedThreadPool
    // no needs errors check in afterExecute here cause that pool used for FutureTask with result check already
    private static final ExecutorService threadPoolSimulations = new ThreadPoolExecutor(
            SIMULATION_THREADS,
            SIMULATION_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            new XmageThreadFactory(ThreadUtils.THREAD_PREFIX_AI_SIMULATION_MAD)
    );
    protected int maxDepth;
    protected int maxNodes;
    protected int maxThinkTimeSecs;
    protected LinkedList<Ability> actions = new LinkedList<>();
    protected List<UUID> targets = new ArrayList<>();
    protected List<String> choices = new ArrayList<>();
    protected Combat combat;
    protected int currentScore;
    protected SimulationNode2 root;
    List<Permanent> attackersList = new ArrayList<>();
    List<Permanent> attackersToCheck = new ArrayList<>();

    protected Set<String> actionCache;

    /**
     * DARRELLBEST-FORK: the weights this player's evaluator uses. Never null.
     * <p>
     * Held by the REAL player, which is the object that searches and scores: {@code SimulatedPlayer2}
     * extends the shared {@code ComputerPlayer}, not this class, and holds no reference to either
     * scoring class; {@code SimulationNode2} holds only a UUID. So every evaluation in the tree runs
     * through this instance's {@link #evaluateState}, and one field here governs the whole search.
     * <p>
     * Shared by reference with every copy (it is immutable) rather than cloned, matching how
     * {@code ComputerPlayerLearner} shares its {@code federation} and {@code session}.
     */
    protected final CommanderEvalParams evalParams;

    private static final List<TreeOptimizer> optimizers = new ArrayList<>();
    protected int lastLoggedTurn = 0; // for debug logs: mark start of the turn
    protected static final String BLANKS = "...............................................";

    static {
        optimizers.add(new WrongCodeUsageOptimizer());
        optimizers.add(new LevelUpOptimizer());
        optimizers.add(new EquipOptimizer());
        optimizers.add(new DiscardCardOptimizer());
        optimizers.add(new OutcomeOptimizer());
    }

    public ComputerPlayer6(String name, RangeOfInfluence range, int skill) {
        this(name, range, skill, CommanderEvalParams.DEFAULT);
    }

    /**
     * DARRELLBEST-FORK: construct with tuned evaluation weights.
     *
     * @param evalParams the weights this player scores positions with; {@code CommanderEvalParams.DEFAULT}
     *                   reproduces the historical hand-tuned behaviour exactly
     */
    public ComputerPlayer6(String name, RangeOfInfluence range, int skill, CommanderEvalParams evalParams) {
        super(name, range);
        if (skill < 4) {
            maxDepth = 4; // TODO: can be increased to support better calculations? (example = 8, skill * 2)
        } else {
            maxDepth = skill;
        }
        maxThinkTimeSecs = skill * 3;
        maxNodes = MAX_SIMULATED_NODES_PER_CALC;
        this.actionCache = new HashSet<>();
        if (evalParams == null) {
            throw new IllegalArgumentException("evalParams must not be null");
        }
        this.evalParams = evalParams;
    }

    /**
     * DARRELLBEST-FORK: keep a hand that can actually cast spells, and never bottom the lands.
     * <p>
     * Reported from a live game and confirmed in the audit log: the bot passed SIX consecutive
     * turns without so much as a land drop, then played its first land on turn 7.
     * <p>
     * Two upstream problems combine. {@code ComputerPlayer.chooseMulligan} keeps unconditionally
     * once {@code hand.size() < 6} -- with zero lands, it keeps. And under the London mulligan the
     * cards to put on the bottom are chosen through a generic {@link TargetCardInHand}, which the
     * AI answers with its ordinary target logic; that logic knows nothing about mulligans and will
     * happily bottom the lands, which is how a kept hand becomes a landless one.
     * <p>
     * This override answers the bottoming question directly: bottom the most expensive cards first
     * and protect a working land count, so the hand that survives is the one that can operate.
     */
    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game,
            java.util.Map<String, java.io.Serializable> options) {
        if (bottomWorstCards(target, source, game)) {
            return true;
        }
        return super.choose(outcome, target, source, game, options);
    }

    /**
     * DARRELLBEST-FORK: the London mulligan asks which cards to put on the bottom through
     * {@link #chooseTarget}, NOT through {@code choose}.
     * <p>
     * This override is the whole reason the bottoming logic works at all. It was originally written
     * only on {@code choose(Outcome, Target, Ability, Game, Map)}, and {@code LondonMulligan} calls
     * {@code player.chooseTarget(Outcome.Discard, target, null, game)} -- a different method. The
     * land protection was therefore dead code from the day it was written: every mulligan in every
     * game, benchmark and live, still bottomed cards through the generic AI target logic, which is
     * exactly the path that throws away lands. The reported symptom it was meant to fix (a kept hand
     * with no lands, then six turns of Pass) was never actually addressed.
     * <p>
     * Nothing detected this. The bench has no mulligan assertion, the parameter sweeps could not
     * separate a dead setting from an ineffective one, and {@code smartMulligan} sat in TUNED
     * looking like a measured win the entire time.
     */
    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (bottomWorstCards(target, source, game)) {
            return true;
        }
        return super.chooseTarget(outcome, target, source, game);
    }

    /** DARRELLBEST-FORK: does this card make mana? Sol Ring, signets, dorks -- the ramp a slow hand needs. */
    private static boolean producesMana(Card c, Game game) {
        for (mage.abilities.Ability a : c.getAbilities(game)) {
            if (a instanceof mage.abilities.mana.ManaAbility) {
                return true;
            }
        }
        return false;
    }

    /** DARRELLBEST-FORK: does this card draw? Detected by effect type, so it needs no card list. */
    private static boolean drawsCards(Card c, Game game) {
        for (mage.abilities.Ability a : c.getAbilities(game)) {
            for (mage.abilities.effects.Effect e : a.getAllEffects()) {
                if (e.getClass().getSimpleName().contains("DrawCard")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Bottoms the worst cards for a London mulligan, protecting a workable land count.
     *
     * @return true when this call answered the target (so the caller must not fall through)
     */
    private boolean bottomWorstCards(Target target, Ability source, Game game) {
        {
            if (evalParams.getSmartMulligan() >= 1
                && target instanceof TargetCardInHand
                && target.getMessage(game) != null
                && target.getMessage(game).contains("bottom of your library")) {
            int toBottom = target.getMaxNumberOfTargets() > 0
                    ? target.getMaxNumberOfTargets() : target.getMinNumberOfTargets();
            List<Card> ordered = new ArrayList<>(hand.getCards(game));
            int landsHeld = 0;
            for (Card c : ordered) {
                if (c.isLand(game)) {
                    landsHeld++;
                }
            }
            final int lands = landsHeld;
            // DARRELLBEST-FORK: keep a hand that always has SOMETHING to do, not merely one that
            // has lands.
            //
            // Protecting lands alone still left hands of three lands and four uncastable bombs,
            // which play out as a land drop and a pass for the first six turns -- the bot has cards
            // and does nothing with them, which is exactly how it feels to play against.
            //
            // So the shed order is by category, worst first: expensive spells go before cheap ones,
            // and mana rocks and card draw are protected alongside lands, because those are what
            // turn a slow hand into an operating one. Ramp finds the next land, draw finds the next
            // play, and cheap spells are things it can actually cast on curve.
            //
            // This only chooses among cards already drawn -- it is the London mulligan's own
            // question ("which of these go to the bottom"), answered well rather than arbitrarily.
            final int keepLands = Math.min(lands, 3);
            final java.util.Map<java.util.UUID, Integer> keepRank = new java.util.HashMap<>();
            int rocksKept = 0;
            int drawKept = 0;
            for (Card c : ordered) {
                int rank;
                if (c.isLand(game)) {
                    rank = 0;                       // never shed first
                } else if (producesMana(c, game) && rocksKept < 2) {
                    rank = 1;                       // ramp: finds the rest of the hand
                    rocksKept++;
                } else if (drawsCards(c, game) && drawKept < 2) {
                    rank = 1;                       // draw: finds the next play
                    drawKept++;
                } else if (c.getManaValue() <= 3) {
                    rank = 2;                       // castable on curve
                } else {
                    rank = 3;                       // expensive and situational: shed first
                }
                keepRank.put(c.getId(), rank);
            }
            ordered.sort((a, b) -> {
                int ra = keepRank.getOrDefault(a.getId(), 3);
                int rb = keepRank.getOrDefault(b.getId(), 3);
                if (ra != rb) {
                    return rb - ra;                 // highest rank (worst) shed first
                }
                return b.getManaValue() - a.getManaValue();
            });
            int chosen = 0;
            int landsBottomed = 0;
            for (Card c : ordered) {
                if (chosen >= toBottom) {
                    break;
                }
                if (c.isLand(game) && lands - landsBottomed <= keepLands) {
                    continue; // protect a workable land count
                }
                if (target.canTarget(getId(), c.getId(), source, game)) {
                    target.add(c.getId(), game);
                    chosen++;
                    if (c.isLand(game)) {
                        landsBottomed++;
                    }
                }
            }
            if (chosen > 0) {
                if (!game.isSimulation() && playLog.isInfoEnabled()) {
                    playLog.info(String.format("MULLIGAN %s | bottomed %d card(s), keeping %d land(s) of %d",
                            getName(), chosen, lands - landsBottomed, lands));
                    AuditLog.event("MULLIGAN", game, getName(), null, null,
                            String.format("\"bottomed\":%d,\"landsKept\":%d,\"landsHeld\":%d",
                                    chosen, lands - landsBottomed, lands));
                }
                return true;
            }
            }
        }
        return false;
    }

    /**
     * DARRELLBEST-FORK: refuse an optional mana payment that costs more than the source produces.
     * <p>
     * Upstream answers YES to every optional cost:
     * <pre>
     *   // Be proactive! Always use abilities, the evaluation function will decide if it's good
     *   return outcome != Outcome.AIDontUseIt;
     * </pre>
     * That comment is wrong about its own mechanism. {@code chooseUse} is a direct yes/no asked
     * during resolution — no evaluation happens, and the search never gets to compare paying against
     * not paying. It is a hardcoded yes.
     * <p>
     * Reported from live games: the bot untaps Mana Vault every single upkeep. Mana Vault is
     * {@code DoIfCostPaid(UntapSourceEffect, GenericManaCost(4), "Pay {4} to untap {this}?")} and it
     * taps for {@code {C}{C}{C}} — so it pays four mana to gain three, every turn, forever. Grim
     * Monolith is the same shape.
     * <p>
     * So: when the prompt asks for a generic mana payment and the source is itself a mana producer,
     * decline if the asking price is at least what the source makes. Deliberately narrow — it only
     * fires on mana-for-mana decisions on our own mana rock, and every other optional cost keeps
     * upstream's proactive behaviour, because "always yes" is right far more often than it is wrong.
     * <p>
     * The known cost of this rule: a line that genuinely wants the untap for its own sake (an
     * untapper combo, or needing the mana available at instant speed later) is given up. That is a
     * worse trade than paying 4 for 3 every turn from now until the game ends.
     */
    @Override
    public boolean chooseUse(Outcome outcome, String message, String secondMessage, String trueText,
            String falseText, Ability source, Game game) {
        if (evalParams.getDeclineLosingManaPayments() >= 1 && message != null && source != null) {
            java.util.regex.Matcher m = GENERIC_MANA_ASK.matcher(message);
            if (m.find()) {
                int asked = Integer.parseInt(m.group(1));
                int produced = manaProducedBy(source.getSourceObject(game), game);
                if (produced > 0 && asked >= produced) {
                    if (!game.isSimulation() && playLog.isInfoEnabled()) {
                        playLog.info(String.format("DECLINE %s | T%d.%s | '%s' costs %d, source makes %d",
                                getName(), game.getTurnNum(), game.getTurnStepType(),
                                message, asked, produced));
                    }
                    return false;
                }
            }
        }
        return super.chooseUse(outcome, message, secondMessage, trueText, falseText, source, game);
    }

    private static final java.util.regex.Pattern GENERIC_MANA_ASK =
            java.util.regex.Pattern.compile("\\{(\\d+)\\}");

    /** Most mana a single activation of this object's mana abilities can make, or 0 if it makes none. */
    private int manaProducedBy(MageObject sourceObject, Game game) {
        if (!(sourceObject instanceof Permanent)) {
            return 0;
        }
        int best = 0;
        for (Ability ability : ((Permanent) sourceObject).getAbilities(game)) {
            if (!(ability instanceof mage.abilities.mana.ManaAbility)) {
                continue;
            }
            for (mage.Mana netMana : ((mage.abilities.mana.ManaAbility) ability).getNetMana(game)) {
                best = Math.max(best, netMana.count());
            }
        }
        return best;
    }

    /**
     * DARRELLBEST-FORK: choose a modal ability's mode by VALUE rather than by declaration order.
     * <p>
     * Upstream's {@code chooseMode} is {@code .findFirst()} over the legal modes, filtered only by
     * {@code canChoose} — legality, never value. Whichever mode the card happens to declare first
     * wins every time.
     * <p>
     * The case that motivated this: Kairi, the Swirling Sky's dies-trigger declares
     * "return any number of target nonland permanents with total mana value 6 or less to their
     * OWNERS' hands" first, and "mill six, then return up to two instants/sorceries" second. When
     * the only legal bounce targets are the bot's own permanents, upstream bounces its own board.
     * That is actively harmful, not merely suboptimal, and the alternative mode would have drawn it
     * two cards.
     * <p>
     * <b>Scoring is by target ownership, deliberately not by {@link mage.constants.Outcome}.</b>
     * Outcome cannot answer this: returning a permanent to hand is good when it is an opponent's
     * and usually bad when it is your own, and the enum carries one value for the effect either way.
     * Who the legal targets belong to does answer it, and needs no simulation.
     * <p>
     * The self-target penalty is a PENALTY, not an exclusion, so a mode that is the only one
     * available is still returned. Ties keep declaration order, matching upstream when nothing
     * distinguishes the modes.
     */
    @Override
    public Mode chooseMode(Modes modes, Ability source, Game game) {
        // upstream's fast path: modes already chosen (the simulated-game case)
        if (modes.getMode() != null && modes.getMaxModes(game, source) == modes.getSelectedModes().size()) {
            return modes.getMode();
        }
        if (evalParams.getModeSelectionMode() == 0) {
            return super.chooseMode(modes, source, game);
        }
        List<Mode> available = new ArrayList<>();
        for (Mode mode : modes.getAvailableModes(source, game)) {
            if (!modes.isMayChooseSameModeMoreThanOnce() && modes.getSelectedModes().contains(mode.getId())) {
                continue;
            }
            if (mode.getTargets().canChoose(source.getControllerId(), source, game)) {
                available.add(mode);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        Mode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Mode mode : available) {
            int score = scoreMode(mode, source, game);
            if (score > bestScore) { // strict >, so ties keep declaration order
                bestScore = score;
                best = mode;
            }
        }
        // DARRELLBEST-FORK: modal choices are a classic silent misplay -- upstream took whichever
        // mode the card declared first, so "why did it bounce its own board instead of drawing two"
        // had no answer anywhere. Real games only; the search calls this constantly.
        if (best != null && available.size() > 1 && !game.isSimulation() && playLog.isInfoEnabled()) {
            playLog.info(String.format("MODE %s | T%d.%s | chose '%s' (score %d) from %d options | %s",
                    getName(), game.getTurnNum(), game.getTurnStepType(),
                    best.getEffects().getText(best), bestScore, available.size(),
                    source.getRule()));
        }
        return best;
    }

    /** Penalty for a targeted mode whose only legal targets are things this player controls. */
    private static final int MODE_SELF_TARGET_PENALTY = 1000;

    private int scoreMode(Mode mode, Ability source, Game game) {
        int score = 0;
        for (Target target : mode.getTargets()) {
            int mine = 0;
            int theirs = 0;
            for (UUID id : target.possibleTargets(source.getControllerId(), source, game)) {
                UUID controller = null;
                Permanent permanent = game.getPermanent(id);
                if (permanent != null) {
                    controller = permanent.getControllerId();
                } else if (game.getPlayer(id) != null) {
                    controller = id;
                }
                if (controller == null) {
                    continue;
                }
                if (controller.equals(getId())) {
                    mine++;
                } else {
                    theirs++;
                }
            }
            if (theirs == 0 && mine > 0) {
                // every legal target is our own -- this mode can only be pointed at ourselves
                score -= MODE_SELF_TARGET_PENALTY;
            } else {
                score += theirs;
            }
        }
        return score;
    }

    public ComputerPlayer6(final ComputerPlayer6 player) {
        super(player);
        this.maxDepth = player.maxDepth;
        this.currentScore = player.currentScore;
        if (player.combat != null) {
            this.combat = player.combat.copy();
        }
        this.actions.addAll(player.actions);
        this.targets.addAll(player.targets);
        this.choices.addAll(player.choices);
        this.actionCache = player.actionCache;
        // DARRELLBEST-FORK: shared by reference, not copied -- CommanderEvalParams is immutable.
        //
        // This line is the whole reason CommanderEvalParams exists as a threaded field rather than a
        // static: the copy constructor is where injected state goes to die. Two fields of this very
        // class, maxNodes and maxThinkTimeSecs, are already missing from this constructor (inherited
        // from upstream) -- a player copy silently reverts to whatever its constructor set. Dropping
        // evalParams the same way would produce a bot that ACCEPTS a tuned config, reports it, and
        // then searches with stock weights the moment the first copy is made, with no error anywhere.
        // CommanderEvalParamsCopyTest exists specifically to fail if this line is ever removed here
        // or in any subclass's copy constructor.
        this.evalParams = player.evalParams;
    }

    /** DARRELLBEST-FORK: the weights this player scores positions with. Never null. */
    public CommanderEvalParams getEvalParams() {
        return evalParams;
    }

    /**
     * Change simulation timeout - used for AI stability tests only
     */
    public void setMaxThinkTimeSecs(int maxThinkTimeSecs) {
        this.maxThinkTimeSecs = maxThinkTimeSecs;
    }

    /**
     * DARRELLBEST-FORK: the single point where a game state becomes a number, and the reason this
     * module is a source fork at all.
     * <p>
     * Upstream MAD calls {@code GameStateEvaluator2.evaluate(...).getTotalScore()} inline at 13
     * places, and GameStateEvaluator2 is a public final class, so there is no way to change how MAD
     * evaluates a position without editing a class shared by every bot on the server. Hoisting all
     * 13 into this one overridable method is what makes the evaluation function replaceable per
     * player -- {@code ComputerPlayerLearner} overrides exactly this to substitute learned weights.
     * <p>
     * ALL 13 had to move, not just the convenient ones. Several evaluated a different game object
     * ({@code node.getGame()}, {@code sim}, {@code prevGame}) via {@code this.getId()} rather than
     * {@code playerId}; leaving any of them on the static path would mean a subclass's evaluator
     * governed some comparisons while the hand-tuned one governed others, and a minimax search whose
     * scores come from two different functions compares values that are not on the same scale. That
     * is a silent, plausible-looking wrongness -- the search still returns a move, just an incoherent
     * one. {@code playerId} and {@code this.getId()} are the same value here, so routing both through
     * a single (game) parameter loses nothing.
     * <p>
     * Behaviour is unchanged from MAD: this returns exactly what the inline calls returned.
     *
     * @param game the state to score, which may be a simulated future rather than the real game
     * @return this player's score for that state, higher being better for this player
     */
    /**
     * DARRELLBEST-FORK: the game state at the leaf of the principal variation from the last search,
     * or null if no usable tree exists.
     * <p>
     * Exists for TD-Leaf. A learned evaluator that feeds a minimax search must be trained on the
     * positions the search actually scores, and those are the LEAVES of the principal variation --
     * the root's value is not computed by the evaluator at all, it is backed up from a leaf. Training
     * the root instead applies the gradient to a position the evaluator never scored, so the update
     * does not correspond to anything that changes the search's output. Samuel hit this, Beal and
     * Smith rediscovered it in 1997, and Baxter, Tridgell and Weaver named the fix TD-Leaf(lambda)
     * (KnightCap, arXiv cs/9901002). TD-Gammon avoided the problem only because it used no search.
     * <p>
     * The walk follows whichever child carries the same backed-up score as its parent, which is the
     * definition of the principal variation in a minimax tree. Ties pick the first such child; any
     * of them is a legitimate PV, and picking a consistent one matters more than picking a canonical
     * one.
     */
    protected Game principalVariationLeaf() {
        SimulationNode2 node = this.root;
        if (node == null) {
            return null;
        }
        int guard = 0;
        while (!node.children.isEmpty() && guard++ < 64) {
            SimulationNode2 next = null;
            for (SimulationNode2 child : node.children) {
                if (child.score == node.score) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            node = next;
        }
        return node.getGame();
    }

    protected int evaluateState(Game game) {
        return GameStateEvaluator2.evaluate(playerId, game, evalParams).getTotalScore();
    }

    @Override
    public ComputerPlayer6 copy() {
        return new ComputerPlayer6(this);
    }

    protected void printBattlefieldScore(Game game, String info) {
        if (logger.isInfoEnabled()) {
            logger.info("");
            logger.info("=================== " + info + ", turn " + game.getTurnNum() + ", " + game.getPlayer(game.getPriorityPlayerId()).getName() + " ===================");
            logger.info("[Stack]: " + game.getStack());
            printBattlefieldScore(game, playerId);
            for (UUID opponentId : game.getOpponents(playerId)) {
                printBattlefieldScore(game, opponentId);
            }
        }
    }

    protected void printBattlefieldScore(Game game, UUID playerId) {
        // hand
        Player player = game.getPlayer(playerId);
        GameStateEvaluator2.PlayerEvaluateScore score = GameStateEvaluator2.evaluate(playerId, game, evalParams);
        logger.info(new StringBuilder("[").append(game.getPlayer(playerId).getName()).append("]")
                .append(", life = ").append(player.getLife())
                .append(", score = ").append(score.getTotalScore())
                .append(" (").append(score.getPlayerInfoFull()).append(")")
                .toString());
        String cardsInfo = player.getHand().getCards(game).stream()
                .map(card -> card.getName() + ":" + evalParams.getHandCardScore()) // TODO: add card score here after implement
                .collect(Collectors.joining("; "));
        StringBuilder sb = new StringBuilder("-> Hand: [")
                .append(cardsInfo)
                .append("]");
        logger.info(sb.toString());

        // battlefield
        sb.setLength(0);
        String ownPermanentsInfo = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isOwnedBy(player.getId()))
                .map(p -> p.getName()
                        + (p.isTapped() ? ",tapped" : "")
                        + (p.isAttacking() ? ",attacking" : "")
                        + (p.getBlocking() > 0 ? ",blocking" : "")
                        + ":" + GameStateEvaluator2.evaluatePermanent(p, game, true, evalParams))
                .collect(Collectors.joining("; "));
        sb.append("-> Permanents: [").append(ownPermanentsInfo).append("]");
        logger.info(sb.toString());
    }

    /**
     * DARRELLBEST-FORK: records what the bot was holding when it chose to do nothing in a main
     * phase.
     * <p>
     * The reported symptom this exists to make visible: "it has mana sources but just never plays
     * its commander or cards from hand to build a field", and separately sitting on a full hand and
     * open mana in order to dump everything on someone else's turn. A bare "Pass" line cannot
     * distinguish that from a legitimate pass with an empty hand or no untapped lands, so the two
     * were indistinguishable in every log written so far.
     * <p>
     * Only its OWN main phases are logged. Passing on an opponent's turn, or in combat, is
     * ordinary -- flagging those would bury the real cases under thousands of correct ones.
     */
    private void logIdlePass(Game game) {
        if (game.isSimulation() || !playLog.isInfoEnabled()) {
            return;
        }
        try {
            if (!playerId.equals(game.getActivePlayerId())
                    || (game.getTurnStepType() != PhaseStep.PRECOMBAT_MAIN
                        && game.getTurnStepType() != PhaseStep.POSTCOMBAT_MAIN)) {
                return;
            }
            int openMana = 0;
            for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
                if (!p.isTapped() && p.getAbilities(game).stream().anyMatch(a -> a instanceof mage.abilities.mana.ManaAbility)) {
                    openMana++;
                }
            }
            int idleScore = evaluateState(game);
            playLog.info(String.format("IDLE %s | T%d.%s | %d card(s) in hand, %d untapped mana source(s) | score %d",
                    getName(), game.getTurnNum(), game.getTurnStepType(),
                    getHand().size(), openMana, idleScore));
            AuditLog.event("IDLE", game, getName(), null, idleScore,
                    String.format("\"hand\":%d,\"mana\":%d", getHand().size(), openMana));
        } catch (Exception e) {
            logger.debug("idle-pass log failed", e);
        }
    }

    /**
     * DARRELLBEST-FORK: how many refused actions one action chain may skip before the bot gives up
     * and passes. A chain refusing this many in a row is not hitting one unplayable card, it is
     * failing to find anything it can legally do, and continuing to grind through it wastes time
     * that the search could spend on the next priority.
     */
    private static final int MAX_REFUSALS_PER_CHAIN = 3;

    /**
     * DARRELLBEST-FORK: emit a feature snapshot for EVERY seat, once per turn, so real games become
     * training data -- including games this bot is merely a participant in.
     * <p>
     * The learner only ever learned from its OWN games: won()/lost() fire on a ComputerPlayerLearner
     * instance, so a human playing against Computer - commander produced nothing at all. That threw
     * away the most valuable data available, because a human winning is a demonstration of a line
     * worth imitating, and there is no supply of those in self-play.
     * <p>
     * Recording every seat and labelling each trajectory with that seat's actual result at game end
     * also gives a strictly better target than the online TD update: TD bootstraps toward the
     * learner's OWN prediction, which is how a model reinforces what it already believes, whereas a
     * finished game supplies the real answer for every player in it -- winner and losers alike.
     * <p>
     * Emitted to the audit stream rather than trained inline: training belongs offline where it can
     * see whole games, and the server should not be running gradient updates on a game thread.
     */
    private void logFeatureSnapshot(Game game) {
        if (game.isSimulation() || !AuditLog.enabled()) {
            return;
        }
        try {
            // once per turn, and only from one seat, so N bots in a pod do not emit N copies
            if (game.getTurnStepType() != PhaseStep.PRECOMBAT_MAIN
                    || !playerId.equals(game.getActivePlayerId())
                    || game.getTurnNum() == lastSnapshotTurn) {
                return;
            }
            lastSnapshotTurn = game.getTurnNum();
            for (java.util.UUID seat : game.getState().getPlayerList(game.getStartingPlayerId())) {
                mage.players.Player p = game.getPlayer(seat);
                if (p == null) {
                    continue;
                }
                double[] f = mage.player.ai.commander.learn.StateFeatures.extract(seat, game);
                if (f == null) {
                    continue;
                }
                StringBuilder vec = new StringBuilder("\"features\":[");
                for (int i = 0; i < f.length; i++) {
                    if (i > 0) {
                        vec.append(',');
                    }
                    vec.append(String.format(java.util.Locale.ROOT, "%.4f", f[i]));
                }
                vec.append(']');
                AuditLog.event("FEATURES", game, p.getName(), null, null, vec.toString());
            }
        } catch (Exception e) {
            logger.debug("feature snapshot failed", e);
        }
    }

    /** DARRELLBEST-FORK: last turn a feature snapshot was emitted, so it happens once per turn. */
    private int lastSnapshotTurn = -1;

    protected void act(Game game) {
        if (actions == null
                || actions.isEmpty()) {
            logFeatureSnapshot(game);
            logIdlePass(game);
            pass(game);
        } else {
            logFeatureSnapshot(game);
            boolean usedStack = false;
            boolean refusedAction = false;
            int refusals = 0;
            while (actions.peek() != null) {
                Ability ability = actions.poll();
                // example: ===> SELECTED ACTION for PlayerA: Play Swamp
                logger.info(String.format("===> SELECTED ACTION for %s: %s",
                        getName(),
                        getAbilityAndSourceInfo(game, ability, true)
                ));
                // DARRELLBEST-FORK: same information, on a logger the server actually records.
                playLog.info(String.format("PLAY %s | T%d.%s | %s",
                        getName(),
                        game.getTurnNum(),
                        game.getTurnStepType(),
                        getAbilityAndSourceInfo(game, ability, true)
                ));
                AuditLog.event("PLAY", game, getName(), getAbilityAndSourceInfo(game, ability, true), null, null);
                if (!ability.getTargets().isEmpty()) {
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                            }
                        }
                    }
                }
                // DARRELLBEST-FORK: honour a REFUSED activation instead of discarding the result.
                //
                // Upstream ignores this return value. When ComputerPlayer7's chained-activation cap
                // refuses an ability, nothing reaches the stack, so usedStack stays false, the bot
                // never passes, it keeps priority, re-runs the whole search, re-derives the SAME
                // ability, and is refused again. The cap stops the action but not the decision loop.
                //
                // Measured on the live server, one Free For All game: refusals logged at 4x, 5x, 6x,
                // 8x then 9x consecutively, spanning 18:12 to 18:24 -- twelve minutes of real time
                // for a single ability, each cycle paying for a complete search to be told "no".
                //
                // If an action we already committed to is refused, the computed chain is stale:
                // drop the rest of it and pass. Passing loses the remainder of that priority, which
                // is exactly the trade the cap is already making, and it costs one search instead of
                // dozens.
                if (!this.activateAbility((ActivatedAbility) ability, game)) {
                    // DARRELLBEST-FORK: skip the refused action and keep going, rather than
                    // throwing the whole turn away.
                    //
                    // Clearing the queue here was too blunt. Arena logs show most refusals are not
                    // loops at all but casts the AI planned and then could not legally make --
                    // Force of Will aimed at Chrome Mox during its own precombat main (it targets a
                    // spell, and the stack was empty), Flawless Maneuver, The Ur-Dragon. One
                    // unplayable spell cost the bot every remaining land drop, cast and activation
                    // that turn: 69 abandoned turns across tonight's runs, 14 of them from a failed
                    // cast that had nothing to do with a loop.
                    //
                    // The original 12-minute hang this guarded against came from act() ignoring the
                    // return value and retrying forever, which skipping does not reintroduce: the
                    // action is dropped, not retried. Two independent backstops still bound a real
                    // loop -- the per-step chained-activation cap, and the per-step priority budget
                    // -- and MAX_REFUSALS_PER_CHAIN below stops a chain that is refusing everything.
                    logger.warn("AI action refused; skipping it and continuing: "
                            + getAbilityAndSourceInfo(game, ability, true));
                    refusals++;
                    if (refusals >= MAX_REFUSALS_PER_CHAIN) {
                        logger.warn("AI refused " + refusals + " actions in one chain; passing");
                        actions.clear();
                        refusedAction = true;
                        break;
                    }
                    continue;
                }
                if (ability.isUsesStack()) {
                    usedStack = true;
                }
            }
            if (usedStack || refusedAction) {
                pass(game);
            }
        }
    }

    protected int addActions(SimulationNode2 node, int depth, int alpha, int beta) {
        boolean stepFinished = false;
        int val;
        if (logger.isTraceEnabled()
                && node != null
                && node.getAbilities() != null
                && !node.getAbilities().toString().equals("[Pass]")) {
            logger.trace("Add Action [" + depth + "] " + node.getAbilities().toString() + "  a: " + alpha + " b: " + beta);
        }
        Game game = node.getGame();
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
            logger.debug("AI game sim interrupted by timeout");
            return evaluateState(game);
        }
        // Condition to stop deeper simulation
        if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
            // how-to fix: make sure you are disabled debug mode by COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS = false
            throw new IllegalStateException("AI ERROR: too much nodes (possible actions)");
        }
        if (depth <= 0
                || SimulationNode2.nodeCount > maxNodes
                || game.checkIfGameIsOver()) {
            val = evaluateState(game);
            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder("Add Actions -- reached end state  <").append(val).append('>');
                SimulationNode2 logNode = node;
                do {
                    sb.append(new StringBuilder(" <- [" + logNode.getDepth() + ']' + (logNode.getAbilities() != null ? logNode.getAbilities().toString() : "[empty]")));
                    logNode = logNode.getParent();
                } while ((logNode.getParent() != null));
                logger.trace(sb);
            }
        } else if (!node.getChildren().isEmpty()) {
            if (logger.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                        .append("] -- something added children ")
                        .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                        .append(" added children: ").append(node.getChildren().size()).append(" (");
                for (SimulationNode2 logNode : node.getChildren()) {
                    sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                }
                sb.append(')');
                logger.debug(sb);
            }
            val = minimaxAB(node, depth - 1, alpha, beta);
        } else {
            logger.trace("Add Action -- alpha: " + alpha + " beta: " + beta + " depth:" + depth + " step:" + game.getTurnStepType() + " for player:" + game.getPlayer(game.getActivePlayerId()).getName());
            if (allPassed(game)) {
                if (!game.getStack().isEmpty()) {
                    resolve(node, depth, game);
                } else {
                    stepFinished = true;
                }
            }

            if (game.checkIfGameIsOver()) {
                val = evaluateState(game);
            } else if (stepFinished) {
                logger.debug("Step finished");
                int testScore = evaluateState(game);
                if (game.isActivePlayer(playerId)) {
                    if (testScore < currentScore) {
                        // if score at end of step is worse than original score don't check further
                        //logger.debug("Add Action -- abandoning check, no immediate benefit");
                        val = testScore;
                    } else {
                        val = evaluateState(game);
                    }
                } else {
                    val = evaluateState(game);
                }
            } else if (!node.getChildren().isEmpty()) {
                if (logger.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                            .append("] -- trigger ")
                            .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                            .append(" added children: ").append(node.getChildren().size()).append(" (");
                    for (SimulationNode2 logNode : node.getChildren()) {
                        sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                    }
                    sb.append(')');
                    logger.debug(sb);
                }
                val = minimaxAB(node, depth - 1, alpha, beta);
            } else {
                val = simulatePriority(node, game, depth, alpha, beta);
            }
        }
        node.setScore(val);
        logger.trace("returning -- score: " + val + " depth:" + depth + " step:" + game.getTurnStepType() + " for player:" + game.getPlayer(node.getPlayerId()).getName());
        return val;

    }

    protected boolean getNextAction(Game game) {
        if (root != null
                && !root.children.isEmpty()) {
            SimulationNode2 test = root;
            root = root.children.get(0);
            while (!root.children.isEmpty()
                    && !root.playerId.equals(playerId)) {
                test = root;
                root = root.children.get(0);
            }
            logger.trace("Sim getNextAction -- game value:" + game.getState().getValue(true) + " test value:" + test.gameValue);
            if (root.playerId.equals(playerId)
                    && root.abilities != null
                    && game.getState().getValue(true).hashCode() == test.gameValue) {
                logger.info("simulating -- continuing previous actions chain");
                actions = new LinkedList<>(root.abilities);
                combat = root.combat;
                return true;
            } else {
                if (root.abilities == null || root.abilities.isEmpty()) {
                    logger.info("simulating -- need re-calculation (no more actions)");
                } else if (game.getState().getValue(true).hashCode() != test.gameValue) {
                    logger.info("simulating -- need re-calculation (game state changed between actions)");
                } else if (!root.playerId.equals(playerId)) {
                    // TODO: need research, why need playerId and why it taken from stack objects as controller
                    logger.info("simulating -- need re-calculation (active controller changed)");
                } else {
                    logger.info("simulating -- need re-calculation (unknown reason)");
                }
                return false;
            }
        }
        return false;
    }

    protected int minimaxAB(SimulationNode2 node, int depth, int alpha, int beta) {
        logger.trace("Sim minimaxAB [" + depth + "] -- a: " + alpha + " b: " + beta + " <" + (node != null ? node.getScore() : "null") + '>');
        UUID currentPlayerId = node.getGame().getPlayerList().get();
        SimulationNode2 bestChild = null;
        for (SimulationNode2 child : node.getChildren()) {
            Combat _combat = child.getCombat();
            if (alpha >= beta) {
                break;
            }
            if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
                throw new IllegalStateException("AI ERROR: too much nodes (possible actions)");
            }
            if (SimulationNode2.nodeCount > maxNodes) {
                break;
            }
            int val = addActions(child, depth - 1, alpha, beta);
            if (!currentPlayerId.equals(playerId)) {
                if (val < beta) {
                    beta = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.LOSE_GAME_SCORE) {
                    logger.debug("lose - break");
                    break;
                }
            } else {
                if (val > alpha) {
                    alpha = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.WIN_GAME_SCORE) {
                    logger.debug("win - break");
                    break;
                }
            }
        }
        node.children.clear();
        if (bestChild != null) {
            node.children.add(bestChild);
        }
        if (!currentPlayerId.equals(playerId)) {
            return beta;
        } else {
            return alpha;
        }
    }

    protected SearchEffect getSearchEffect(StackAbility ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof SearchEffect) {
                return (SearchEffect) effect;
            }
        }
        return null;
    }

    protected void resolve(SimulationNode2 node, int depth, Game game) {
        StackObject stackObject = game.getStack().getFirstOrNull();
        if (stackObject == null) {
            throw new IllegalStateException("Catch empty stack on resolve (something wrong with sim code)");
        }
        if (stackObject instanceof StackAbility) {
            // AI hint for search effects (calc all possible cards for best score)
            SearchEffect effect = getSearchEffect((StackAbility) stackObject);
            if (effect != null
                    && stackObject.getControllerId().equals(playerId)) {
                Target target = effect.getTarget();
                if (!target.isChoiceCompleted(getId(), (StackAbility) stackObject, game, null)) {
                    for (UUID targetId : target.possibleTargets(stackObject.getControllerId(), stackObject.getStackAbility(), game)) {
                        Game sim = game.createSimulationForAI();
                        StackAbility newAbility = (StackAbility) stackObject.copy();
                        SearchEffect newEffect = getSearchEffect(newAbility);
                        newEffect.getTarget().addTarget(targetId, newAbility, sim);
                        sim.getStack().push(sim, newAbility);
                        SimulationNode2 newNode = new SimulationNode2(node, sim, depth, stackObject.getControllerId());
                        node.children.add(newNode);
                        newNode.getTargets().add(targetId);
                        logger.trace("Sim search -- node#: " + SimulationNode2.getCount() + " for player: " + sim.getPlayer(stackObject.getControllerId()).getName());
                    }
                    return;
                }
            }
        }
        stackObject.resolve(game);
        if (stackObject instanceof StackAbility) {
            game.getStack().remove(stackObject, game);
        }
        game.applyEffects();
        game.getPlayers().resetPassed();
        game.getPlayerList().setCurrent(game.getActivePlayerId());
    }

    /**
     * Base call for simulation of AI actions
     *
     * @return
     */
    protected Integer addActionsTimed() {
        // TODO: all actions added and calculated one by one,
        //  multithreading do not supported here
        // run new game simulation in parallel thread
        FutureTask<Integer> task = new FutureTask<>(() -> addActions(root, maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE));
        threadPoolSimulations.execute(task);
        try {
            int maxSeconds = maxThinkTimeSecs;
            if (COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS) {
                maxSeconds = 3600;
            }
            logger.debug("maxThink: " + maxSeconds + " seconds ");
            Integer res = task.get(maxSeconds, TimeUnit.SECONDS);
            if (res != null) {
                return res;
            }
        } catch (TimeoutException | InterruptedException e) {
            // AI thinks too long
            // how-to fix: look at stack info - it can contain bad ability with infinite choose dialog
            logger.warn("");
            logger.warn("AI player thinks too long (report it to github):");
            logger.warn(" - player: " + getName());
            logger.warn(" - battlefield size: " + root.game.getBattlefield().getAllPermanents().size());
            logger.warn(" - stack: " + root.game.getStack());
            logger.warn(" - game: " + root.game);
            printFreezeNode(root);
            logger.warn("");
            task.cancel(true);
        } catch (ExecutionException e) {
            // game error
            logger.error("AI player catch game error in simulation - " + getName() + " - " + root.game + ": " + e, e);
            task.cancel(true);
            // real games: must catch and log
            // unit tests: must raise again for fast fail
            if (this.isTestMode() && this.isFastFailInTestMode()) {
                throw new IllegalStateException("One of the simulated games raise the error: " + e, e);
            }
        } catch (Throwable e) {
            // ?
            logger.error("AI simulation catch unknown error: " + e, e);
            task.cancel(true);
        }
        //TODO: timeout handling
        return 0;
    }

    private void printFreezeNode(SimulationNode2 root) {
        // print simple tree - there are possible multiple child nodes, but ignore it - same for abilities
        List<String> chain = new ArrayList<>();
        SimulationNode2 node = root;
        while (node != null) {
            if (node.abilities != null && !node.abilities.isEmpty()) {
                Ability ability = node.abilities.get(0);
                String sourceInfo = CardUtil.getSourceIdName(node.game, ability);
                chain.add(String.format("%s: %s",
                        (sourceInfo.isEmpty() ? "unknown" : sourceInfo),
                        ability
                ));
            }
            node = node.children == null || node.children.isEmpty() ? null : node.children.get(0);
        }
        logger.warn("Possible freeze chain:");
        if (root != null && chain.isEmpty()) {
            logger.warn(" - unknown use case (too many possible targets?)"); // maybe can't finish any calc, maybe related to target options
        }
        chain.forEach(s -> {
            logger.warn(" - " + s);
        });
    }

    protected int simulatePriority(SimulationNode2 node, Game game, int depth, int alpha, int beta) {
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
            logger.debug("AI game sim interrupted by timeout");
            return evaluateState(game);
        }
        node.setGameValue(game.getState().getValue(true).hashCode());
        SimulatedPlayer2 currentPlayer = (SimulatedPlayer2) game.getPlayer(game.getPlayerList().get());
        SimulationNode2 bestNode = null;
        List<Ability> allActions = currentPlayer.simulatePriority(game);
        optimize(game, allActions);
        int startedScore = evaluateState(node.getGame());
        if (logger.isInfoEnabled()
                && !allActions.isEmpty()
                && depth == maxDepth) {
            logger.info(String.format("POSSIBLE ACTION CHAINS for %s (%d, started score: %d)%s",
                    getName(),
                    allActions.size(),
                    startedScore,
                    (actions.isEmpty() ? "" : ":")
            ));
            for (int i = 0; i < allActions.size(); i++) {
                // print possible actions with detailed targets
                Ability possibleAbility = allActions.get(i);
                logger.info(String.format("-> #%d (%s)", i + 1, getAbilityAndSourceInfo(game, possibleAbility, true)));
            }
        }
        int actionNumber = 0;
        int bestValSubNodes = Integer.MIN_VALUE;
        for (Ability action : allActions) {
            actionNumber++;
            if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS && Thread.currentThread().isInterrupted()) {
                logger.info("Sim Prio [" + depth + "] -- interrupted");
                break;
            }
            Game sim = game.createSimulationForAI();
            if (!(action instanceof StaticAbility) //for MorphAbility, etc
                    && sim.getPlayer(currentPlayer.getId()).activateAbility((ActivatedAbility) action.copy(), sim)) {
                sim.applyEffects();
                if (checkForRepeatedAction(sim, node, action, currentPlayer.getId())) {
                    logger.debug("Sim Prio [" + depth + "] -- repeated action: " + action);
                    continue;
                }
                if (!sim.checkIfGameIsOver()
                        && (action.isUsesStack() || action instanceof PassAbility)) {
                    // skip priority for opponents before stack resolve
                    UUID nextPlayerId = sim.getPlayerList().get();
                    do {
                        sim.getPlayer(nextPlayerId).pass(game);
                        nextPlayerId = sim.getPlayerList().getNext();
                    } while (!Objects.equals(nextPlayerId, this.getId()));
                }
                SimulationNode2 newNode = new SimulationNode2(node, sim, action, depth, currentPlayer.getId());
                sim.checkStateAndTriggered();
                int finalScore;
                if (action instanceof PassAbility && sim.getStack().isEmpty()) {
                    // no more next actions, it's a final score
                    finalScore = evaluateState(sim);
                } else {
                    // resolve current action and calc all next actions to find best score (return max possible score)
                    finalScore = addActions(newNode, depth - 1, alpha, beta);
                }
                logger.debug("Sim Prio " + BLANKS.substring(0, 2 + (maxDepth - depth) * 3) + '[' + depth + "]#" + actionNumber + " <" + finalScore + "> - (" + action + ") ");

                // Hints on data:
                // * node - started game with executed command (pay and put on stack)
                // * newNode - resolved game with resolved command (resolve stack)
                // * node.children - rewrites to store only best tree (e.g. contains only final data)
                // * node.score - rewrites to store max score (e.g. contains only final data)
                if (logger.isInfoEnabled()
                        && depth >= maxDepth) {
                    // show final calculated score and best actions chain from it
                    List<SimulationNode2> fullChain = new ArrayList<>();
                    fullChain.add(newNode);
                    SimulationNode2 finalNode = newNode;
                    while (!finalNode.getChildren().isEmpty()) {
                        finalNode = finalNode.getChildren().get(0);
                        fullChain.add(finalNode);
                    }

                    // example: Sim Prio [6] #1 <diff -19, +4444> (Lightning Bolt [aa5]: Cast Lightning Bolt -> Balduvian Bears [c49])
                    // total
                    logger.info(String.format("Sim Prio [%d] #%d <total score diff %s (from %s to %s)>",
                            depth,
                            actionNumber,
                            printDiffScore(finalScore - startedScore),
                            printDiffScore(startedScore),
                            printDiffScore(finalScore)
                    ));

                    // details
                    for (int chainIndex = 0; chainIndex < fullChain.size(); chainIndex++) {
                        SimulationNode2 currentNode = fullChain.get(chainIndex);
                        SimulationNode2 prevNode;
                        if (chainIndex == 0) {
                            prevNode = node;
                        } else {
                            prevNode = fullChain.get(chainIndex - 1);
                        }

                        int currentScore = evaluateState(currentNode.getGame());
                        int prevScore = evaluateState(prevNode.getGame());

                        if (currentNode.getAbilities() != null) {
                            // ON PRIORITY

                            // runtime check
                            if (currentNode.getAbilities().size() != 1) {
                                throw new IllegalStateException("AI's simulated game must contains only one selected action, but found: " + currentNode.getAbilities());
                            }
                            if (!currentNode.getTargets().isEmpty() || !currentNode.getChoices().isEmpty()) {
                                throw new IllegalStateException("WTF, simulated abilities with targets/choices");
                            }
                            logger.info(String.format("Sim Prio [%d] -> next action: [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    getAbilityAndSourceInfo(currentNode.getGame(), currentNode.getAbilities().get(0), true)
                            ));
                        } else if (!currentNode.getTargets().isEmpty()) {
                            // ON TARGETS
                            String targetsInfo = currentNode.getTargets()
                                    .stream()
                                    .map(id -> {
                                        Player player = game.getPlayer(id);
                                        if (player != null) {
                                            return player.getName();
                                        }
                                        MageObject object = game.getObject(id);
                                        if (object != null) {
                                            return object.getIdName();
                                        }
                                        return "unknown";
                                    })
                                    .collect(Collectors.joining(", "));
                            logger.info(String.format("Sim Prio [%d] -> with possible choices: [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    targetsInfo)
                            );
                        } else if (!currentNode.getChoices().isEmpty()) {
                            // ON CHOICES
                            String choicesInfo = String.join(", ", currentNode.getChoices());
                            logger.info(String.format("Sim Prio [%d] -> with possible choices (must not see that code): [%d]<diff %s> (%s)",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore),
                                    choicesInfo)
                            );
                        } else {
                            logger.info(String.format("Sim Prio [%d] -> with do nothing: [%d]<diff %s>",
                                    depth,
                                    currentNode.getDepth(),
                                    printDiffScore(currentScore - prevScore))
                            );
                        }
                    }
                }

                if (currentPlayer.getId().equals(playerId)) {
                    if (finalScore > bestValSubNodes) {
                        bestValSubNodes = finalScore;
                    }
                    if (depth == maxDepth
                            && action instanceof PassAbility) {
                        finalScore = finalScore - PASSIVITY_PENALTY; // passivity penalty
                    }
                    if (finalScore > alpha
                            || (depth == maxDepth
                            && finalScore == alpha
                            && RandomUtil.nextBoolean())) { // Adding random for equal value to get change sometimes
                        alpha = finalScore;
                        bestNode = newNode;
                        bestNode.setScore(finalScore);
                        if (!newNode.getChildren().isEmpty()) {
                            // TODO: wtf, must review all code to remove shared objects
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }

                        // keep only best node
                        if (depth == maxDepth) {
                            logger.info("Sim Prio [" + depth + "] -* BEST actions chain so far: <final score " + bestNode.getScore() + ">");
                            node.children.clear();
                            node.children.add(bestNode);
                            node.setScore(bestNode.getScore());
                        }
                    }

                    // no need to check other actions
                    if (finalScore == GameStateEvaluator2.WIN_GAME_SCORE) {
                        logger.debug("Sim Prio -- win - break");
                        break;
                    }
                } else {
                    if (finalScore < beta) {
                        beta = finalScore;
                        bestNode = newNode;
                        bestNode.setScore(finalScore);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                    }

                    // no need to check other actions
                    if (finalScore == GameStateEvaluator2.LOSE_GAME_SCORE) {
                        logger.debug("Sim Prio -- lose - break");
                        break;
                    }
                }
                if (alpha >= beta) {
                    break;
                }
                if (SimulationNode2.nodeCount > MAX_SIMULATED_NODES_PER_ERROR) {
                    throw new IllegalStateException("AI ERROR: too many nodes (possible actions)");
                }
                if (SimulationNode2.nodeCount > maxNodes) {
                    logger.debug("Sim Prio -- reached end-state");
                    break;
                }
            }
        } // end of for (allActions)

        if (depth == maxDepth) {
            // TODO: buggy? Why it ended with depth limit 6 on one Pass action?!
            logger.info("Sim Prio [" + depth + "] ## Ended due max actions chain depth limit (" + maxDepth + ") -- Nodes calculated: " + SimulationNode2.nodeCount);
        }
        if (bestNode != null) {
            node.children.clear();
            node.children.add(bestNode);
            node.setScore(bestNode.getScore());
            if (logger.isTraceEnabled()
                    && !bestNode.getAbilities().toString().equals("[Pass]")) {
                logger.trace(new StringBuilder("Sim Prio [").append(depth).append("] -- Set after (depth=").append(depth).append(")  <").append(bestNode.getScore()).append("> ").append(bestNode.getAbilities().toString()).toString());
            }
        }

        if (currentPlayer.getId().equals(playerId)) {
            return bestValSubNodes;
        } else {
            return beta;
        }
    }

    protected String getAbilityAndSourceInfo(Game game, Ability ability, boolean showTargets) {
        // ability
        // TODO: add modal info
        // + (action.isModal() ? " Mode = " + action.getModes().getMode().toString() : "")
        if (ability.isModal()) {
            //throw new IllegalStateException("TODO: need implement");
        }
        MageObject sourceObject = ability.getSourceObject(game);
        String abilityInfo = (sourceObject == null ? "" : sourceObject.getIdName() + ": ") + CardUtil.substring(ability.toString(), 30, "...");
        // targets
        String targetsInfo = "";
        if (showTargets) {
            List<String> allTargetsInfo = new ArrayList<>();
            ability.getAllSelectedTargets().forEach(target -> {
                target.getTargets().forEach(selectedId -> {
                    String xInfo = "";
                    if (target instanceof TargetAmount) {
                        xInfo = "x" + target.getTargetAmount(selectedId) + " ";
                    }

                    String targetInfo = null;
                    Player player = game.getPlayer(selectedId);
                    if (player != null) {
                        targetInfo = player.getName();
                    }
                    if (targetInfo == null) {
                        MageObject object = game.getObject(selectedId);
                        if (object != null) {
                            targetInfo = object.getIdName();
                        }
                    }
                    if (targetInfo == null) {
                        StackObject stackObject = game.getState().getStack().getStackObject(selectedId);
                        if (stackObject != null) {
                            targetInfo = CardUtil.substring(stackObject.toString(), 20, "...");
                        }
                    }
                    if (targetInfo == null) {
                        targetInfo = "unknown";
                    }
                    allTargetsInfo.add(xInfo + targetInfo);
                });
            });
            targetsInfo = String.join(" + ", allTargetsInfo);
        }
        return abilityInfo + (targetsInfo.isEmpty() ? "" : " -> " + targetsInfo);
    }

    private String printDiffScore(int score) {
        if (score >= 0) {
            return "+" + score;
        } else {
            return "" + score;
        }
    }

    /**
     * Various AI optimizations for actions.
     *
     * @param game
     * @param allActions
     */
    protected void optimize(Game game, List<Ability> allActions) {
        for (TreeOptimizer optimizer : optimizers) {
            optimizer.optimize(game, allActions);
        }
        Collections.sort(allActions, new Comparator<Ability>() {
            @Override
            public int compare(Ability ability1, Ability ability2) {
                String rule1 = ability1.toString();
                String rule2 = ability2.toString();

                // pass
                boolean pass1 = rule1.startsWith("Pass");
                boolean pass2 = rule2.startsWith("Pass");
                if (pass1 != pass2) {
                    if (pass1) {
                        return 1;
                    } else {
                        return -1;
                    }
                }

                // play
                boolean play1 = rule1.startsWith("Play");
                boolean play2 = rule2.startsWith("Play");
                if (play1 != play2) {
                    if (play1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // cast
                boolean cast1 = rule1.startsWith("Cast");
                boolean cast2 = rule2.startsWith("Cast");
                if (cast1 != cast2) {
                    if (cast1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // DARRELLBEST-FORK: within a category, try the BIGGEST play first instead of the
                // alphabetically-first one. Alpha-beta only prunes when a good move is examined
                // early -- ordering by rule text gave the search no help at all, so it explored
                // close to the full tree. Mana value is a crude but free proxy for impact, and any
                // ordering correlated with strength beats one correlated with spelling.
                int mv1 = ability1.getManaCosts() == null ? 0 : ability1.getManaCosts().manaValue();
                int mv2 = ability2.getManaCosts() == null ? 0 : ability2.getManaCosts().manaValue();
                if (mv1 != mv2) {
                    return mv2 - mv1;
                }

                // default
                return ability1.getRule().compareTo(ability2.getRule());
            }
        });
    }

    protected boolean allPassed(Game game) {
        for (Player player : game.getPlayers().values()) {
            if (!player.isPassed()
                    && !player.hasLost()
                    && !player.hasLeft()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (choices.isEmpty()) {
            return super.choose(outcome, choice, game);
        }
        if (!choice.isChosen()) {
            if (!choice.setChoiceByAnswers(choices, true)) {
                choice.setRandomChoice();
            }
        }
        return true;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());
        if (!target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
            for (UUID targetId : targets) {
                target.addTarget(targetId, source, game);
                if (target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.choose(outcome, cards, target, source, game);
        }

        UUID abilityControllerId = target.getAffectedAbilityControllerId(getId());
        if (!target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
            for (UUID targetId : targets) {
                target.add(targetId, game);
                if (target.isChoiceCompleted(abilityControllerId, source, game, cards)) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void declareBlockers(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, activePlayerId, activePlayerId))) {
            List<Permanent> attackers = getAttackers(game);
            if (attackers == null) {
                return;
            }

            List<Permanent> possibleBlockers = super.getAvailableBlockers(game);
            possibleBlockers = filterOutNonblocking(game, attackers, possibleBlockers);
            if (possibleBlockers.isEmpty()) {
                return;
            }

            attackers = filterOutUnblockable(game, attackers, possibleBlockers);
            if (attackers.isEmpty()) {
                return;
            }

            CombatUtil.sortByPower(attackers, false); // most powerfull go to first

            // DARRELLBEST-FORK: the bot models blocking with ITS OWN weights.
            //
            // CombatUtil's five evaluations all score from the DEFENDING player's perspective, read
            // out of game.getCombat().getDefenders() -- which is not necessarily this bot. Passing
            // evalParams here therefore means "predict the defender's outcome using my valuation of
            // creatures, life and cards", not "use the defender's own weights".
            //
            // That is the right call, and the alternative is worse. There is no way to know another
            // player's weights (a human has none, and a differently tuned bot's are private to it),
            // so the only options are this bot's weights or a hardcoded stock set. A hardcoded set
            // would reintroduce exactly the split this refactor removes: a bot tuned to value life
            // cheaply would still refuse chump blocks, because the block decision would be scored on
            // a scale its own search never uses. Self-modelling keeps every number the search
            // compares on one scale, which is the property that makes the comparisons mean anything.
            CombatInfo combatInfo = CombatUtil.blockWithGoodTrade2(game, attackers, possibleBlockers, evalParams);
            Player player = game.getPlayer(playerId);

            boolean blocked = false;
            for (Map.Entry<Permanent, List<Permanent>> entry : combatInfo.getCombat().entrySet()) {
                UUID attackerId = entry.getKey().getId();
                List<Permanent> blockers = entry.getValue();
                if (blockers != null) {
                    for (Permanent blocker : blockers) {
                        // TODO: buggy or miss on multi blocker requirements?!
                        player.declareBlocker(player.getId(), blocker.getId(), attackerId, game);
                        blocked = true;
                    }
                }
            }
            if (blocked) {
                game.getPlayers().resetPassed();
            }
        }
    }

    private List<Permanent> filterOutNonblocking(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> blockersLeft = new ArrayList<>();
        for (Permanent blocker : blockers) {
            for (Permanent attacker : attackers) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    blockersLeft.add(blocker);
                    break;
                }
            }
        }
        return blockersLeft;
    }

    private List<Permanent> filterOutUnblockable(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> attackersLeft = new ArrayList<>();
        for (Permanent attacker : attackers) {
            if (CombatUtil.canBeBlocked(game, attacker, blockers)) {
                attackersLeft.add(attacker);
            }
        }
        return attackersLeft;
    }

    private List<Permanent> getAttackers(Game game) {
        Set<UUID> attackersUUID = game.getCombat().getAttackers();
        if (attackersUUID.isEmpty()) {
            return null;
        }

        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : attackersUUID) {
            Permanent permanent = game.getPermanent(attackerId);
            attackers.add(permanent);
        }
        return attackers;
    }

    /**
     * Choose attackers based on static information. That means that AI won't
     * look to the future as it was before, but just choose attackers based on
     * current state of the game. This is worse, but at least it is easier to
     * implement and won't lead to the case when AI doesn't do anything -
     * neither attack nor block.
     *
     * @param game
     * @param activePlayerId
     */
    private void declareAttackers(Game game, UUID activePlayerId) {
        attackersToCheck.clear();
        attackersList.clear();
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, activePlayerId, activePlayerId))) {
            Player attackingPlayer = game.getPlayer(activePlayerId);

            // check alpha strike first (all in attack to kill a player)
            for (UUID defenderId : game.getOpponents(playerId, true)) {
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }

                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);
                List<Permanent> killers = CombatUtil.canKillOpponent(game, attackersList, possibleBlockers, defender);
                if (!killers.isEmpty()) {
                    for (Permanent attacker : killers) {
                        attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
                    }
                    return;
                }
            }

            // TODO: add game simulations here to find best attackers/blockers combination

            // find safe attackers (can't be killed by blockers)
            // DARRELLBEST-FORK: iterate by index so each pass knows how many opponents are still to
            // come, which is what lets the attack be divided rather than dumped on the first one.
            List<UUID> defenderOrder = new ArrayList<>(game.getOpponents(playerId, true));
            for (int defenderIndex = 0; defenderIndex < defenderOrder.size(); defenderIndex++) {
                UUID defenderId = defenderOrder.get(defenderIndex);
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }
                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);

                // The AI will now attack more sanely.  Simple, but good enough for now.
                // The sim minmax does not work at the moment.
                boolean safeToAttack;
                CombatEvaluator eval = new CombatEvaluator();

                for (Permanent attacker : attackersList) {
                    safeToAttack = true;
                    int attackerValue = eval.evaluate(attacker, game);
                    for (Permanent blocker : possibleBlockers) {
                        int blockerValue = eval.evaluate(blocker, game);

                        // blocker can kill attacker
                        if (attacker.getPower().getValue() <= blocker.getToughness().getValue()
                                && attacker.getToughness().getValue() <= blocker.getPower().getValue()) {
                            safeToAttack = false;
                        }

                        // attacker and blocker have the same P/T, check their overall value
                        if (attacker.getToughness().getValue() == blocker.getPower().getValue()
                                && attacker.getPower().getValue() == blocker.getToughness().getValue()) {
                            if (attackerValue > blockerValue
                                    || blocker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().contains(new ExaltedAbility())
                                    || blocker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().contains(new ExaltedAbility())) {
                                safeToAttack = false;
                            }
                        }

                        // attacker can kill by deathtouch
                        if (attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                || attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // attacker has flying and blocker has neither flying nor reach
                        if (attacker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(ReachAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // if any check fails, move on to the next possible attacker
                        if (!safeToAttack) {
                            break;
                        }
                    }

                    // DARRELLBEST-FORK: attack when it is PROFITABLE, not only when it is risk-free.
                    //
                    // Upstream only attacks with creatures no possible blocker could kill, so a
                    // single large untapped blocker shuts down the whole attack step. Caught in a
                    // live audit log as "NO ATTACKS | T6 | 3 untapped creature(s) available".
                    //
                    // Level 1 -- outnumbering: if there are more available attackers than possible
                    // blockers, the surplus connects however blocks are assigned, so attacking is
                    // profitable even though no individual attacker is "safe". This is the whole
                    // plan of a go-wide deck.
                    //
                    // Level 2 -- favourable trades: attack into a lethal blocker when the attacker
                    // is worth materially less than the blocker that must eat it. Trading a 1/1
                    // token for a 5/5 is good play that level 0 refuses on principle.
                    if (!safeToAttack && evalParams.getAttackAggression() >= 1
                            && attackersList.size() > possibleBlockers.size()) {
                        safeToAttack = true;
                    }
                    if (!safeToAttack && evalParams.getAttackAggression() >= 2) {
                        int best = 0;
                        for (Permanent blocker : possibleBlockers) {
                            best = Math.max(best, eval.evaluate(blocker, game));
                        }
                        if (best > attackerValue * 2) {
                            safeToAttack = true;
                        }
                    }

                    // 0 power, don't bother attacking
                    if (attacker.getPower().getValue() == 0) {
                        safeToAttack = false;
                    }

                    // add attacker to the next list of all attackers that can safely attack
                    if (safeToAttack) {
                        attackersToCheck.add(attacker);
                    }
                }

                // find possible target for attack (priority: planeswalker -> battle -> player)
                int totalPowerOfAttackers = 0;
                int usedPowerOfAttackers = 0;
                for (Permanent attacker : attackersToCheck) {
                    totalPowerOfAttackers += attacker.getPower().getValue();
                }

                // TRY ATTACK PLANESWALKER + BATTLE
                List<Permanent> possiblePermanentDefenders = new ArrayList<>();
                // planeswalker first priority
                game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_PLANESWALKER, activePlayerId, game)
                        .stream()
                        .filter(p -> p.canBeAttacked(null, defenderId, game))
                        .forEach(possiblePermanentDefenders::add);
                // battle second priority
                game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_BATTLE, activePlayerId, game)
                        .stream()
                        .filter(p -> p.canBeAttacked(null, defenderId, game))
                        .forEach(possiblePermanentDefenders::add);

                for (Permanent permanentDefender : possiblePermanentDefenders) {
                    if (usedPowerOfAttackers >= totalPowerOfAttackers) {
                        break;
                    }
                    int currentCounters;
                    if (permanentDefender.isPlaneswalker(game)) {
                        currentCounters = permanentDefender.getCounters(game).getCount(CounterType.LOYALTY);
                    } else if (permanentDefender.isBattle(game)) {
                        currentCounters = permanentDefender.getCounters(game).getCount(CounterType.DEFENSE);
                    } else {
                        // impossible error (SBA must remove all planeswalkers/battles with 0 counters before declare attackers)
                        throw new IllegalStateException("AI: can't find counters for defending permanent " + permanentDefender.getName(), new Throwable());
                    }

                    // attack anyway (for kill or damage)
                    // TODO: add attackers optimization here (1 powerfull + min number of additional permanents,
                    //  current code uses random/etb order)
                    for (Permanent attackingPermanent : attackersToCheck) {
                        if (attackingPermanent.isAttacking()) {
                            // already used for another target
                            continue;
                        }
                        attackingPlayer.declareAttacker(attackingPermanent.getId(), permanentDefender.getId(), game, true);
                        currentCounters -= attackingPermanent.getPower().getValue();
                        usedPowerOfAttackers += attackingPermanent.getPower().getValue();
                        if (currentCounters <= 0) {
                            break;
                        }
                    }
                }

                // TRY ATTACK PLAYER
                // DARRELLBEST-FORK: divide the attack across opponents instead of giving the whole
                // team to whoever happens to be first in the list.
                //
                // Upstream declares EVERY remaining attacker against this defender; the next
                // opponent then finds isAttacking() already true for all of them and is skipped. In
                // a Free For All -- what the live server actually runs -- the bot could never split.
                //
                // Greedy on purpose: send exactly enough power to kill this opponent when that is
                // on the table, otherwise an even share of what is left, keeping the rest for the
                // opponents still to come. Searching assignments of N attackers over M defenders is
                // M^N, the same explosion that already makes this bot time out on big boards.
                List<Permanent> stillFree = new ArrayList<>();
                for (Permanent attackingPermanent : attackersToCheck) {
                    if (!attackingPermanent.isAttacking()) {
                        stillFree.add(attackingPermanent);
                    }
                }
                int toSend = stillFree.size();
                int defendersLeft = defenderOrder.size() - defenderIndex;
                if (evalParams.getMultiplayerAttackSplit() >= 1 && defendersLeft > 1 && !stillFree.isEmpty()) {
                    Player defendingPlayer = game.getPlayer(defenderId);
                    int lifeToBeat = defendingPlayer == null ? Integer.MAX_VALUE : defendingPlayer.getLife();
                    int needed = 0;
                    int power = 0;
                    for (Permanent attackingPermanent : stillFree) {
                        needed++;
                        power += attackingPermanent.getPower().getValue();
                        if (power >= lifeToBeat) {
                            break;
                        }
                    }
                    // lethal is worth committing to; otherwise take a fair share and move on
                    toSend = (power >= lifeToBeat) ? needed
                            : Math.max(1, stillFree.size() / defendersLeft);
                }
                for (int i = 0; i < toSend && i < stillFree.size(); i++) {
                    attackingPlayer.declareAttacker(stillFree.get(i).getId(), defenderId, game, true);
                }
            }
        }
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        logger.debug("selectAttackers");
        declareAttackers(game, playerId);
        logCombatDecision(game, "ATTACK");
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        logger.debug("selectBlockers");
        declareBlockers(game, playerId);
        logCombatDecision(game, "BLOCK");
    }

    /**
     * DARRELLBEST-FORK: record the combat decision actually taken, so a misplay can be read off the
     * log instead of reproduced.
     * <p>
     * Combat is where bad play is most visible and least explicable after the fact -- a suicidal
     * attack or a refused block leaves no trace anywhere else. Attacks and blocks never went through
     * {@code act()}, so the PLAY lines there do not cover them.
     * <p>
     * Gated on {@code !game.isSimulation()}: the search declares attackers and blockers thousands of
     * times per real decision inside simulated copies, and logging those would bury the one line
     * that matters and reintroduce exactly the log volume this logger exists to avoid.
     * <p>
     * Also records a NO ATTACKS / NO BLOCKS line with the count of creatures that were available.
     * "It had six untapped creatures and attacked with none" is the single most common complaint
     * about this bot, and silence in a log cannot distinguish "chose not to" from "never asked".
     */
    private void logCombatDecision(Game game, String what) {
        if (game.isSimulation() || !playLog.isInfoEnabled()) {
            return;
        }
        try {
            // DARRELLBEST-FORK: how many creatures COULD legally have acted, logged on every
            // combat line. Without it a "BLOCK" line that assigns no blockers is unreadable:
            // declining to block with nothing able to block is forced, declining with three able is
            // a decision worth auditing, and the old line looked identical either way. Arena logs
            // had the bot letting a 6/6 lifelinker through on four consecutive turns with no way to
            // tell which of the two it was.
            //
            // "Untapped" is NOT the test, and using it made this metric lie in both directions.
            // For attackers it over-counts: a first read of these logs turned up the bot declining
            // to attack with 8 and 9 "available" creatures, which looked like a serious misplay and
            // was nothing of the sort -- a swarm deck had just deployed a board of summoning-sick
            // tokens that could not legally attack. For blockers it under-counts, because the count
            // ran after attackers had already tapped. canAttack/canBlock answer the question the
            // line is actually asking, including summoning sickness, defender and tap state.
            boolean blocking = "BLOCK".equals(what);
            int idle = 0;
            for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
                if (!p.isCreature(game)) {
                    continue;
                }
                if (blocking ? p.canBlockAny(game) : p.canAttackInPrinciple(null, game)) {
                    idle++;
                }
            }
            StringBuilder sb = new StringBuilder();
            int groups = 0;
            int used = 0;
            for (CombatGroup group : game.getCombat().getGroups()) {
                List<String> attackers = new ArrayList<>();
                for (UUID id : group.getAttackers()) {
                    Permanent p = game.getPermanent(id);
                    if (p != null && p.getControllerId().equals(playerId) || "BLOCK".equals(what)) {
                        attackers.add(describe(game, id));
                    }
                }
                List<String> blockers = new ArrayList<>();
                for (UUID id : group.getBlockers()) {
                    blockers.add(describe(game, id));
                }
                if (attackers.isEmpty() && blockers.isEmpty()) {
                    continue;
                }
                String defender = game.getPlayer(group.getDefenderId()) != null
                        ? game.getPlayer(group.getDefenderId()).getName()
                        : describe(game, group.getDefenderId());
                sb.append(" [").append(String.join(", ", attackers)).append(" -> ").append(defender);
                if (!blockers.isEmpty()) {
                    sb.append(" blocked by ").append(String.join(", ", blockers));
                    used += blockers.size();
                }
                sb.append(']');
                groups++;
            }
            if (groups == 0) {
                int available = idle;
                int noneScore = evaluateState(game);
                playLog.info(String.format("NO %sS %s | T%d.%s | %d untapped creature(s) available | score %d",
                        what, getName(), game.getTurnNum(), game.getTurnStepType(), available, noneScore));
                AuditLog.event("NO_" + what, game, getName(), null, noneScore,
                        String.format("\"used\":0,\"available\":%d", available));
            } else {
                int combatScore = evaluateState(game);
                playLog.info(String.format("%s %s | T%d.%s |%s | %d of %d untapped used | score %d",
                        what, getName(), game.getTurnNum(), game.getTurnStepType(), sb,
                        blocking ? used : groups, idle, combatScore));
                AuditLog.event(what, game, getName(), sb.toString().trim(), combatScore,
                        String.format("\"used\":%d,\"available\":%d", blocking ? used : groups, idle));
            }
        } catch (Exception e) {
            // an audit log must never be able to break a live game
            logger.debug("play log failed", e);
        }
    }

    private String describe(Game game, UUID id) {
        Permanent p = game.getPermanent(id);
        if (p == null) {
            return String.valueOf(id).substring(0, 8);
        }
        return p.getName() + " " + p.getPower().getValue() + "/" + p.getToughness().getValue();
    }

    /**
     * Copies game and replaces all players in copy with simulated players
     *
     * @param game
     * @return a new game object with simulated players
     */
    protected Game createSimulation(Game game) {
        Game sim = game.createSimulationForAI();
        for (Player oldPlayer : sim.getState().getPlayers().values()) {
            // replace original player by simulated player and find result (execute/resolve current action)
            Player origPlayer = game.getState().getPlayers().get(oldPlayer.getId()).copy();
            SimulatedPlayer2 simPlayer = new SimulatedPlayer2(oldPlayer, oldPlayer.getId().equals(playerId));
            simPlayer.restore(origPlayer);
            sim.getState().getPlayers().put(oldPlayer.getId(), simPlayer);
        }
        return sim;
    }

    private boolean checkForRepeatedAction(Game sim, SimulationNode2 node, Ability action, UUID playerId) {
        // pass or casting two times a spell multiple times on hand is ok
        if (action instanceof PassAbility || action instanceof SpellAbility || action.isManaAbility()) {
            return false;
        }
        int newVal = evaluateState(sim);
        SimulationNode2 test = node.getParent();
        while (test != null) {
            if (test.getPlayerId().equals(playerId)) {
                if (test.getAbilities() != null && test.getAbilities().size() == 1) {
                    if (action.toString().equals(test.getAbilities().get(0).toString())) {
                        if (test.getParent() != null) {
                            Game prevGame = node.getGame();
                            if (prevGame != null) {
                                int oldVal = evaluateState(prevGame);
                                if (oldVal >= newVal) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            test = test.getParent();
        }
        return false;
    }

    @Override
    public void cleanUpOnMatchEnd() {
        root = null;
        super.cleanUpOnMatchEnd();
    }

}
