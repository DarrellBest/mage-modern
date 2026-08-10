package mage.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Instrumentation sink that an LLM-backed player reports into during a game.
 * The invalid-tool-call count is first-class rather than a log line because it
 * is the metric that evaluates custom Modelfiles.
 * <p>
 * Synchronized because the engine may call into a player from more than one
 * thread over a game's lifetime; contention is irrelevant at these call rates.
 *
 * @author Darrell Best
 */
public final class BenchMetrics implements mage.player.ai.kanna.ComputerPlayerKanna.DecisionMetrics {

    private final List<Long> latenciesMs = new ArrayList<>();
    private int invalidToolCalls = 0;

    @Override
    public synchronized void recordLlmCall(long latencyMs) {
        latenciesMs.add(latencyMs);
    }

    @Override
    public synchronized void recordInvalidToolCall() {
        invalidToolCalls++;
    }

    public synchronized LlmStats snapshot() {
        if (latenciesMs.isEmpty()) {
            return new LlmStats(0, 0L, 0L, 0L, invalidToolCalls);
        }
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        long total = 0L;
        for (Long latency : sorted) {
            total += latency;
        }
        return new LlmStats(sorted.size(), total,
                percentile(sorted, 0.50), percentile(sorted, 0.95), invalidToolCalls);
    }

    /**
     * Nearest-rank percentile: the smallest value at or above the given rank.
     */
    private static long percentile(List<Long> sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1);
    }
}
