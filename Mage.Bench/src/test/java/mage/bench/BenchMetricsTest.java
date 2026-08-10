package mage.bench;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BenchMetricsTest {

    @Test
    public void emptyMetrics_reportZeroes() {
        LlmStats stats = new BenchMetrics().snapshot();
        assertEquals(0, stats.calls);
        assertEquals(0L, stats.totalLatencyMs);
        assertEquals(0L, stats.p50LatencyMs);
        assertEquals(0L, stats.p95LatencyMs);
        assertEquals(0, stats.invalidToolCalls);
    }

    @Test
    public void countsCallsAndTotalLatency() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(100L);
        metrics.recordLlmCall(300L);
        LlmStats stats = metrics.snapshot();
        assertEquals(2, stats.calls);
        assertEquals(400L, stats.totalLatencyMs);
    }

    @Test
    public void percentilesUseNearestRankOnSortedLatencies() {
        BenchMetrics metrics = new BenchMetrics();
        // recorded out of order on purpose: snapshot must sort before ranking
        long[] latencies = {500L, 100L, 400L, 200L, 300L, 900L, 700L, 600L, 800L, 1000L};
        for (long latency : latencies) {
            metrics.recordLlmCall(latency);
        }
        LlmStats stats = metrics.snapshot();
        assertEquals(10, stats.calls);
        // nearest-rank: p50 -> ceil(0.50*10)=5th smallest = 500
        assertEquals(500L, stats.p50LatencyMs);
        // nearest-rank: p95 -> ceil(0.95*10)=10th smallest = 1000
        assertEquals(1000L, stats.p95LatencyMs);
    }

    @Test
    public void singleCall_bothPercentilesAreThatCall() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(42L);
        LlmStats stats = metrics.snapshot();
        assertEquals(42L, stats.p50LatencyMs);
        assertEquals(42L, stats.p95LatencyMs);
    }

    @Test
    public void countsInvalidToolCallsSeparatelyFromCalls() {
        BenchMetrics metrics = new BenchMetrics();
        metrics.recordLlmCall(10L);
        metrics.recordInvalidToolCall();
        metrics.recordInvalidToolCall();
        LlmStats stats = metrics.snapshot();
        assertEquals(1, stats.calls);
        assertEquals(2, stats.invalidToolCalls);
    }
}
