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
    public final int turns;
    public final long wallTimeMs;
    public final Termination termination;
    public final String errorMessage;
    public final boolean seatSwapped;
    public final LlmStats llm;

    public GameResult(int gameIndex, long seed, String winner, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped, LlmStats llm) {
        this.gameIndex = gameIndex;
        this.seed = seed;
        this.winner = winner;
        this.turns = turns;
        this.wallTimeMs = wallTimeMs;
        this.termination = termination;
        this.errorMessage = errorMessage;
        this.seatSwapped = seatSwapped;
        this.llm = llm;
    }
}
