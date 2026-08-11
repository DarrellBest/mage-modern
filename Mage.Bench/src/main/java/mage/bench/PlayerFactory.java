package mage.bench;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.ComputerPlayerMCTS;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.players.Player;

/**
 * Builds a benchmark player from a short type key, so any matchup is
 * expressible from the command line without code changes. Kanna-vs-stock and
 * stock-vs-stock therefore share one code path, which is what makes the
 * stock-vs-stock control run a trustworthy check on the harness itself.
 *
 * @author Darrell Best
 */
public final class PlayerFactory {

    public static final String KANNA = "kanna";
    public static final String CP7 = "cp7";
    public static final String MCTS = "mcts";
    public static final String BASE = "base";
    /** DARRELLBEST-FORK: the "Computer - commander" bot, a source fork of MAD tuned independently. */
    public static final String COMMANDER = "commander";

    /**
     * DARRELLBEST-FORK: think-time budget handed to every ComputerPlayer6-derived bench player,
     * replacing the default {@code maxThinkTimeSecs = skill * 3} (6s at skill 2).
     * <p>
     * What this fixes: {@code ComputerPlayer6.addActions} terminates on whichever comes first, the
     * node cap ({@code maxNodes} = 1500) or the wall clock ({@code task.get(maxThinkTimeSecs,
     * TimeUnit.SECONDS)} at ComputerPlayer6:469). With a 6s clock, how deep the AI searched depended
     * on what else the machine happened to be doing, so measured play strength moved with system
     * load. Raising the budget past what a 1500-node search can consume makes the node cap the sole
     * terminator: every decision is now a completed search rather than an abandoned mid-search read.
     * Measured side effect: games got roughly 2x FASTER (mean 51s to 23s), because searches stop at
     * 1500 nodes instead of burning the full clock. Verified by the absence of any "AI player thinks
     * too long" warning in bench logs, which the timeout path always logs.
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
    public static final int BENCH_MAX_THINK_TIME_SECS = 600;

    private PlayerFactory() {
    }

    public static Player create(String type, String name, RangeOfInfluence range, int skill) {
        if (KANNA.equals(type)) {
            return new ComputerPlayerKanna(name, range, skill);
        } else if (CP7.equals(type)) {
            ComputerPlayer7 player = new ComputerPlayer7(name, range, skill);
            player.setMaxThinkTimeSecs(BENCH_MAX_THINK_TIME_SECS);
            return player;
        } else if (COMMANDER.equals(type)) {
            ComputerPlayerCommander player = new ComputerPlayerCommander(name, range, skill);
            player.setMaxThinkTimeSecs(BENCH_MAX_THINK_TIME_SECS);
            return player;
        } else if (MCTS.equals(type)) {
            return new ComputerPlayerMCTS(name, range, skill);
        } else if (BASE.equals(type)) {
            return new ComputerPlayer(name, range);
        }
        throw new IllegalArgumentException("Unknown player type '" + type
                + "', expected one of: " + KANNA + ", " + CP7 + ", " + MCTS + ", " + BASE + ", " + COMMANDER);
    }
}
