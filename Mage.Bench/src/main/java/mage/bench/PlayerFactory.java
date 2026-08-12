package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.commander.ComputerPlayerLearner;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.Player;

/**
 * Builds a benchmark player from a short type key, so any matchup is
 * expressible from the command line without code changes. Fork-vs-stock and
 * stock-vs-stock therefore share one code path, which is what makes the
 * stock-vs-stock control run a trustworthy check on the harness itself.
 *
 * @author Darrell Best
 */
public final class PlayerFactory {

    public static final String CP7 = "cp7";
    public static final String MCTS = "mcts";
    public static final String BASE = "base";
    /** DARRELLBEST-FORK: the "Computer - commander" bot, a source fork of MAD tuned independently. */
    public static final String COMMANDER = "commander";
    /** DARRELLBEST-FORK: the "Computer - learner" bot -- commander's search, learned evaluation. */
    public static final String LEARNER = "learner";

    /**
     * DARRELLBEST-FORK: think-time budget handed to every ComputerPlayer6-derived bench player,
     * replacing the default {@code maxThinkTimeSecs = skill * 3} (6s at skill 2).
     * <p>
     * What this fixes: {@code ComputerPlayer6.addActions} terminates on whichever comes first, the
     * node cap ({@code maxNodes} = 1500) or the wall clock ({@code task.get(maxThinkTimeSecs,
     * TimeUnit.SECONDS)} at ComputerPlayer6:469). With a 6s clock, how deep the AI searched depended
     * on what else the machine happened to be doing, so measured play strength moved with system
     * load. Raising the budget past what a 1500-node search can consume makes the node cap the sole
     * terminator in most positions: a decision is a completed search rather than an abandoned
     * mid-search read, so play strength stops moving with system load.
     * <p>
     * DEFAULT IS OFF, after measuring the cost. The clock is the ONLY bound on per-decision time,
     * and how long 1500 nodes take depends entirely on board complexity, so raising it is not the
     * free win it looked like on the first matchup measured:
     * <pre>
     *   budget   Kairi vs Krenko      Kairi mirror        Kairi vs Krenko throughput
     *   6s       51s/game             (not measured)      ~70 games/hour
     *   600s     23s/game (2x FASTER) 1079s for ONE game  ~2 games/hour on control decks
     *   30s      ~15min/game          (not measured)      ~4 games/hour
     * </pre>
     * The 2x speedup at 600s was real but matchup-specific -- Krenko's cheap goblins make small
     * boards where a completed search is cheap. Generalising from it was wrong. Control decks build
     * large boards where every node costs, and there the clock is the only thing keeping games to a
     * sane length.
     * <p>
     * Throughput is what actually buys reliable numbers here: results are not reproducible (see
     * below), so the defence against noise is sample size, and sample size is throughput. 70
     * games/hour with truncated searches beats 4 games/hour with complete ones. Set the property to
     * experiment -- a completed search IS a better decision, and load-independent strength is worth
     * having when comparing two BOTS rather than two decks -- but it is not the default.
     * <p>
     * What this does NOT fix, despite an earlier claim in this comment that it would: results are
     * still NOT reproducible from a fixed seed. Two runs of identical seed, decks, skill and turn cap
     * still diverge (measured: game 0 ended turn 20 in one run and turn 12 in the other). The cause
     * is {@code mage.util.RandomUtil}, a single process-wide {@code Random}: the AI search runs on a
     * pool thread while the game runs on the main thread, and both draw from that one generator, so
     * the split of the random stream between them is scheduler-dependent. {@code setSeed} fixes the
     * shuffle and nothing else. Reproducibility would require running the search inline instead of on
     * the pool; deliberately not pursued -- treat bench output as a sample, not a replay, and size
     * runs accordingly (n=10 cannot separate a 30% matchup from an even one).
     * <p>
     * Bench-only, and must stay that way. The live server needs the real clock: MAD bots in 4-player
     * Commander were timing out every 6s there, which is why maxNodes was cut to 1500.
     */
    public static final String THINK_TIME_PROPERTY = "xmage.bench.thinkSecs";

    /** @return the configured override, or -1 to leave the stock skill-based budget alone. */
    private static int thinkTimeOverride() {
        try {
            return Integer.parseInt(System.getProperty(THINK_TIME_PROPERTY, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Two nearly identical branches rather than one generic helper: ComputerPlayerCommander extends
    // the FORK's mage.player.ai.commander.ComputerPlayer7, which is a different class from the shared
    // mage.player.ai.ComputerPlayer7, so no single type bound covers both.

    private PlayerFactory() {
    }

    /** Stock weights: equivalent to {@code create(type, name, range, skill, null)}. */
    public static Player create(String type, String name, RangeOfInfluence range, int skill) {
        return create(type, name, range, skill, null);
    }

    /**
     * DARRELLBEST-FORK: builds one bench player, optionally with tuned evaluator weights.
     *
     * @param evalParams weights for this seat, or null for the bot's stock evaluation. Only
     *                   {@link #COMMANDER} and {@link #LEARNER} can accept them; passing non-null
     *                   for any other type is an ERROR, not a no-op -- see below.
     * @throws IllegalArgumentException if {@code type} is unknown, or if {@code evalParams} is
     *                                  non-null for a bot that cannot use it
     */
    public static Player create(String type, String name, RangeOfInfluence range, int skill,
                                CommanderEvalParams evalParams) {
        if (CP7.equals(type)) {
            // NOT a candidate for eval params, despite the fork's ComputerPlayer7 having a params
            // constructor: this key builds mage.player.ai.ComputerPlayer7 from the MAD plugin, a
            // SEPARATE class from mage.player.ai.commander.ComputerPlayer7. MAD carries its own copy
            // of the scoring code (mage.player.ai.util.*) whose weights were never hoisted into
            // CommanderEvalParams, so there is nothing here for a params file to reach. Wiring the
            // commander bot's params object into MAD would be a lie: it would compile and change
            // nothing. Use --playerA=commander to tune this search's weights.
            requireNoEvalParams(type, evalParams);
            ComputerPlayer7 cp7 = new ComputerPlayer7(name, range, skill);
            if (thinkTimeOverride() > 0) {
                cp7.setMaxThinkTimeSecs(thinkTimeOverride());
            }
            return cp7;
        } else if (COMMANDER.equals(type)) {
            ComputerPlayerCommander cmd = evalParams == null
                    ? new ComputerPlayerCommander(name, range, skill)
                    : new ComputerPlayerCommander(name, range, skill, evalParams);
            if (thinkTimeOverride() > 0) {
                cmd.setMaxThinkTimeSecs(thinkTimeOverride());
            }
            return cmd;
        } else if (LEARNER.equals(type)) {
            ComputerPlayerLearner learner = evalParams == null
                    ? new ComputerPlayerLearner(name, range, skill)
                    : new ComputerPlayerLearner(name, range, skill, evalParams);
            if (thinkTimeOverride() > 0) {
                learner.setMaxThinkTimeSecs(thinkTimeOverride());
            }
            return learner;
        } else if (MCTS.equals(type)) {
            requireNoEvalParams(type, evalParams);
            return new ComputerPlayerMCTS(name, range, skill);
        } else if (BASE.equals(type)) {
            requireNoEvalParams(type, evalParams);
            return new ComputerPlayer(name, range);
        }
        throw new IllegalArgumentException("Unknown player type '" + type
                + "', expected one of: " + CP7 + ", " + MCTS + ", " + BASE + ", " + COMMANDER + ", " + LEARNER);
    }

    /**
     * Rejects tuned weights handed to a bot that does not score positions with a
     * {@link CommanderEvalParams}.
     * <p>
     * Loud rather than ignored, for the same reason {@link EvalParamsLoader} refuses an unknown key:
     * a run asked to give one side tuned weights, but silently gave it stock ones, produces a
     * complete-looking result set that answers a different question than the one asked -- and
     * nothing in the output would say so.
     */
    private static void requireNoEvalParams(String type, CommanderEvalParams evalParams) {
        if (evalParams != null) {
            throw new IllegalArgumentException("Player type '" + type + "' does not use "
                    + "CommanderEvalParams, so the eval params given for this seat would be silently "
                    + "ignored. Only '" + COMMANDER + "' and '" + LEARNER + "' accept tuned weights; "
                    + "drop the params option for this seat, or put a bot that uses them on it.");
        }
    }
}
