package mage.player.ai.commander;

import mage.constants.RangeOfInfluence;
import mage.player.ai.commander.score.CommanderEvalParams;

/**
 * DARRELLBEST-FORK: entry point for the "Computer - commander" player type.
 * <p>
 * This is the class named in config.xml, and it exists so the selectable bot has a stable identity
 * that does not move as the fork diverges. Everything below it ({@link ComputerPlayer7},
 * {@link ComputerPlayer6}, the simulated players and the score package) is a source copy of
 * Mage.Player.AI.MAD, currently byte-identical to MAD apart from its package and its own
 * {@code SIMULATION_THREADS} constant. So at this commit the bot plays exactly like MAD by
 * construction, which is the intended starting point: it makes the first benchmark run a control
 * that should come out even against MAD, and any later divergence attributable to a specific change
 * rather than to the fork itself.
 * <p>
 * The three levers this fork exists to reach, none of which are reachable by subclassing MAD:
 * <ul>
 *   <li><b>Evaluation weights</b> (now {@link mage.player.ai.commander.score.CommanderEvalParams},
 *       hoisted out of {@code commander.score.GameStateEvaluator2} so they can be injected and tuned
 *       without a rebuild). Upstream's life curve is a step function that flattens hard: the first
 *       four points of life are worth 1000 each, points 5-10 are worth 500 each, 11-15 are worth 400,
 *       16-20 are worth 200, and everything ABOVE 20 life is worth a flat 100. A card in hand is 5. In
 *       40-life Commander the bot spends the entire game in that flat region, so the real exchange
 *       rate it plays by is 100/5 = twenty cards in hand per point of life. That still reads as
 *       hyper-defensive and close to blind to card advantage -- and it gets worse, not better, as
 *       life drops toward the steep part of the curve.</li>
 *   <li><b>Move ordering.</b> The search terminates on {@code maxNodes} (1500), so how well those
 *       nodes are spent is set by the order candidates are tried; alpha-beta cuts far more with a
 *       good ordering, which buys depth at no extra cost.</li>
 *   <li><b>Combo recognition.</b> A depth-4 search cannot see a three-card line, and no amount of
 *       weight tuning fixes a horizon problem.</li>
 * </ul>
 * Do not tune any of them against the benchmark until its wall-clock non-determinism is confirmed
 * fixed (see {@code mage.bench.PlayerFactory.BENCH_MAX_THINK_TIME_SECS}): the same seed produced
 * 3-7 in one run and 5-5 in another, which is wider than any tuning effect worth having.
 *
 * @author Darrell Best
 */
public class ComputerPlayerCommander extends ComputerPlayer7 {

    public ComputerPlayerCommander(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    /** DARRELLBEST-FORK: construct with tuned evaluation weights. */
    public ComputerPlayerCommander(String name, RangeOfInfluence range, int skill, CommanderEvalParams evalParams) {
        super(name, range, skill, evalParams);
    }

    public ComputerPlayerCommander(final ComputerPlayerCommander player) {
        // evalParams rides along in ComputerPlayer6's copy constructor (shared by reference).
        super(player);
    }

    @Override
    public ComputerPlayerCommander copy() {
        return new ComputerPlayerCommander(this);
    }
}
