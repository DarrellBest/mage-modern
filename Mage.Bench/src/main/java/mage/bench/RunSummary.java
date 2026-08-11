package mage.bench;

/**
 * Aggregated statistics for a benchmark run.
 *
 * @author Darrell Best
 */
public final class RunSummary {

    public final int total;
    public final int decisive;
    public final int winsA;
    public final int winsB;
    public final int caps;
    public final int draws;
    /** Games cut short by the --maxGameSeconds wall-clock budget; excluded from {@link #decisive}. */
    public final int timeouts;
    public final int errors;
    public final double winRateA;
    public final double wilsonLowerA;
    public final double wilsonUpperA;
    public final long p50TurnMs;
    public final long p95TurnMs;
    public final int llmCalls;
    public final int invalidToolCalls;

    public RunSummary(int total, int decisive, int winsA, int winsB, int caps, int draws,
                      int timeouts, int errors,
                      double winRateA, double wilsonLowerA, double wilsonUpperA,
                      long p50TurnMs, long p95TurnMs, int llmCalls, int invalidToolCalls) {
        this.total = total;
        this.decisive = decisive;
        this.winsA = winsA;
        this.winsB = winsB;
        this.caps = caps;
        this.draws = draws;
        this.timeouts = timeouts;
        this.errors = errors;
        this.winRateA = winRateA;
        this.wilsonLowerA = wilsonLowerA;
        this.wilsonUpperA = wilsonUpperA;
        this.p50TurnMs = p50TurnMs;
        this.p95TurnMs = p95TurnMs;
        this.llmCalls = llmCalls;
        this.invalidToolCalls = invalidToolCalls;
    }
}
