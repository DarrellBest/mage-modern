package mage.player.ai.commander;

import mage.constants.RangeOfInfluence;

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
 *   <li><b>Evaluation weights</b> ({@code commander.score.GameStateEvaluator2}). Upstream scores a
 *       point of life at 300 and a card in hand at 5, making one life worth sixty cards in hand.
 *       In 40-life Commander that reads as hyper-defensive and close to blind to card advantage.</li>
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

    public ComputerPlayerCommander(final ComputerPlayerCommander player) {
        super(player);
    }

    @Override
    public ComputerPlayerCommander copy() {
        return new ComputerPlayerCommander(this);
    }
}
