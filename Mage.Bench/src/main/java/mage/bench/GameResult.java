package mage.bench;

/**
 * Outcome of one benchmark game. Written verbatim as one JSON line.
 *
 * @author Darrell Best
 */
public final class GameResult {

    public final int gameIndex;
    public final long seed;
    /** Player key of the winner ("commander", "cp7", ...), or null for a draw/cap/error. */
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
    /**
     * DARRELLBEST-FORK: 1-based seat that side A occupied this game, so "did side A win?" is
     * {@code winnerSeat == seatA} at any table size.
     * <p>
     * A duel could always derive this from {@link #seatSwapped} ({@code swapped ? 2 : 1}), and for
     * a duel this field holds exactly that. A pod cannot: side A may sit in any of N seats, and a
     * boolean has nowhere to put that. Recording it per game also means a reader never has to know
     * the rotation rule that produced it -- if the rotation ever changes, old result files stay
     * correctly interpretable.
     */
    public final int seatA;
    /**
     * DARRELLBEST-FORK: how many players sat at this table.
     * <p>
     * Without it a result file cannot be scored correctly. "Side A won 30% of games" is a strong
     * result at a table of four (the null is 25%, since one of four seats wins) and a bad one at a
     * table of two (the null is 50%). A reader that assumes a duel would call a good pod bot a
     * failure. The baseline is a property of the game that produced the record, so the record
     * carries it rather than leaving it to be remembered alongside the file.
     */
    public final int seats;
    /**
     * DARRELLBEST-FORK: which evaluator weights side A played with -- {@code "default"}, or the
     * params file's absolute path plus a hash of the resolved values (see
     * {@link EvalParamsLoader#describe}). Recorded on EVERY row, not once per run, because
     * {@link MergeReporter} pools rows from many files and sweeps reuse file names: a row that does
     * not carry its own parameters cannot be attributed to a sweep leg once it has been merged, and
     * a results file that does not say which weights produced it is worse than no file at all.
     */
    public final String paramsA;
    /** DARRELLBEST-FORK: side B's evaluator weights; see {@link #paramsA}. */
    public final String paramsB;

    public GameResult(int gameIndex, long seed, String winner, int winnerSeat, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped) {
        this(gameIndex, seed, winner, winnerSeat, turns, wallTimeMs, termination, errorMessage,
                seatSwapped, "default", "default");
    }

    public GameResult(int gameIndex, long seed, String winner, int winnerSeat, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped,
                      String paramsA, String paramsB) {
        this(gameIndex, seed, winner, winnerSeat, turns, wallTimeMs, termination, errorMessage,
                seatSwapped, seatSwapped ? 2 : 1, paramsA, paramsB);
    }

    public GameResult(int gameIndex, long seed, String winner, int winnerSeat, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped, int seatA,
                      String paramsA, String paramsB) {
        this(gameIndex, seed, winner, winnerSeat, turns, wallTimeMs, termination, errorMessage,
                seatSwapped, seatA, 2, paramsA, paramsB);
    }

    public GameResult(int gameIndex, long seed, String winner, int winnerSeat, int turns, long wallTimeMs,
                      Termination termination, String errorMessage, boolean seatSwapped, int seatA,
                      int seats, String paramsA, String paramsB) {
        this.gameIndex = gameIndex;
        this.seed = seed;
        this.winner = winner;
        this.winnerSeat = winnerSeat;
        this.turns = turns;
        this.wallTimeMs = wallTimeMs;
        this.termination = termination;
        this.errorMessage = errorMessage;
        this.seatSwapped = seatSwapped;
        this.seatA = seatA;
        this.seats = seats;
        this.paramsA = paramsA;
        this.paramsB = paramsB;
    }
}
