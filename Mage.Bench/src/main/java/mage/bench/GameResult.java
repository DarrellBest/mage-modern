package mage.bench;

/**
 * Outcome of one benchmark game. Written verbatim as one JSON line.
 *
 * @author Darrell Best
 */
public final class GameResult {

    public final int gameIndex;
    public final long seed;
    /** Player key of the winner ("kanna", "cp7", ...), or null for a draw/cap/error. */
    public final String winner;
    /**
     * Seat of the winner: 1 or 2 for a decisive game, 0 otherwise. The key alone is not enough
     * to attribute a win to "player A" or "player B" when both seats share the same key (e.g. a
     * cp7-vs-cp7 control run) -- the seat is what {@link SummaryReporter} attributes by.
     */
    public final int winnerSeat;
    public final int turns;
    public final long wallTimeMs;
    public final Termination termination;
    public final String errorMessage;
    public final boolean seatSwapped;
    public final LlmStats llm;

    public GameResult(int gameIndex, long seed, String winner, int winnerSeat, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped, LlmStats llm) {
        this.gameIndex = gameIndex;
        this.seed = seed;
        this.winner = winner;
        this.winnerSeat = winnerSeat;
        this.turns = turns;
        this.wallTimeMs = wallTimeMs;
        this.termination = termination;
        this.errorMessage = errorMessage;
        this.seatSwapped = seatSwapped;
        this.llm = llm;
    }
}
