package mage.bench;

/**
 * Immutable snapshot of LLM usage for one game.
 *
 * @author Darrell Best
 */
public final class LlmStats {

    public final int calls;
    public final long totalLatencyMs;
    public final long p50LatencyMs;
    public final long p95LatencyMs;
    public final int invalidToolCalls;

    public LlmStats(int calls, long totalLatencyMs, long p50LatencyMs, long p95LatencyMs, int invalidToolCalls) {
        this.calls = calls;
        this.totalLatencyMs = totalLatencyMs;
        this.p50LatencyMs = p50LatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.invalidToolCalls = invalidToolCalls;
    }

    public static LlmStats empty() {
        return new LlmStats(0, 0L, 0L, 0L, 0);
    }
}
