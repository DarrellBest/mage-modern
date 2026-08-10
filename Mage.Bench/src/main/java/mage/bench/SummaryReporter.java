package mage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates game results into a run summary.
 * <p>
 * Uses a Wilson score interval rather than the normal approximation: at the
 * small N these runs produce, and at rates near 0 or 1, the normal interval
 * gives bounds outside [0, 1] and badly understates uncertainty.
 * <p>
 * Cap, draw, and error games are excluded from the win-rate denominator and
 * reported separately -- "never finished", "finished with no winner", and
 * "failed to complete" all mean something different from "lost".
 * <p>
 * Wins are attributed by seat, not by player key: when both seats run the
 * same player type (a same-key control run, e.g. cp7 vs cp7), the key alone
 * can't tell "player A" and "player B" apart, so attributing by key would
 * silently book every decisive game as a win for A.
 *
 * @author Darrell Best
 */
public final class SummaryReporter {

    /** 95% two-sided normal quantile. */
    private static final double Z = 1.96;

    private SummaryReporter() {
    }

    public static RunSummary summarize(List<GameResult> results, String playerAKey) {
        // playerAKey is unused here -- attribution is by seat (see the loop below), not by
        // key. Kept in the signature for symmetry with format() and because a future caller
        // may want it for validation (e.g. asserting it matches config.playerA).
        int total = results.size();
        int winsA = 0;
        int winsB = 0;
        int caps = 0;
        int draws = 0;
        int errors = 0;
        int llmCalls = 0;
        int invalidToolCalls = 0;
        List<Long> turnTimes = new ArrayList<>();

        for (GameResult result : results) {
            if (result.termination == Termination.CAP) {
                caps++;
            } else if (result.termination == Termination.DRAW) {
                draws++;
            } else if (result.termination == Termination.ERROR) {
                errors++;
            } else {
                // decisive game: attribute by seat, not by key -- player A occupies seat 1
                // on a non-swapped game and seat 2 on a swapped one
                int seatA = result.seatSwapped ? 2 : 1;
                if (result.winnerSeat == seatA) {
                    winsA++;
                } else {
                    winsB++;
                }
            }
            if (result.llm != null) {
                llmCalls += result.llm.calls;
                invalidToolCalls += result.llm.invalidToolCalls;
            }
            if (result.turns > 0) {
                turnTimes.add(result.wallTimeMs / result.turns);
            }
        }

        int decisive = winsA + winsB;
        double winRateA = decisive == 0 ? 0.0 : (double) winsA / decisive;
        double[] interval = wilson(winsA, decisive);

        Collections.sort(turnTimes);
        return new RunSummary(total, decisive, winsA, winsB, caps, draws, errors,
                winRateA, interval[0], interval[1],
                percentile(turnTimes, 0.50), percentile(turnTimes, 0.95),
                llmCalls, invalidToolCalls);
    }

    /**
     * Wilson score interval. Returns {lower, upper}, both clamped to [0, 1].
     */
    static double[] wilson(int successes, int n) {
        if (n == 0) {
            return new double[]{0.0, 0.0};
        }
        double p = (double) successes / n;
        double z2 = Z * Z;
        double denominator = 1.0 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denominator;
        double margin = Z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * n)) / n) / denominator;
        double lower = Math.max(0.0, center - margin);
        double upper = Math.min(1.0, center + margin);
        return new double[]{lower, upper};
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(fraction * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1);
    }

    public static String format(RunSummary summary, String playerAKey, String playerBKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== Benchmark summary ===%n"));
        sb.append(String.format("Games:        %d total, %d decisive, %d cap, %d draw, %d error%n",
                summary.total, summary.decisive, summary.caps, summary.draws, summary.errors));
        sb.append(String.format("%-12s %d wins%n", playerAKey + ":", summary.winsA));
        sb.append(String.format("%-12s %d wins%n", playerBKey + ":", summary.winsB));
        sb.append(String.format("Win rate:     %.1f%% for %s  (95%% CI %.1f%% - %.1f%%)%n",
                summary.winRateA * 100.0, playerAKey,
                summary.wilsonLowerA * 100.0, summary.wilsonUpperA * 100.0));
        sb.append(String.format("Turn time:    p50 %d ms, p95 %d ms%n", summary.p50TurnMs, summary.p95TurnMs));
        sb.append(String.format("LLM:          %d calls, %d invalid tool calls%n",
                summary.llmCalls, summary.invalidToolCalls));
        return sb.toString();
    }
}
