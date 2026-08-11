package mage.bench;

/**
 * How a benchmark game ended. CAP, DRAW, TIMEOUT, and ERROR are all deliberately
 * distinct from a loss: CAP means the game hit the turn cap without a
 * winner, DRAW means the game genuinely ended (or stalled) with no winner
 * before the cap was reached, TIMEOUT means the game was still going when its
 * {@code --maxGameSeconds} wall-clock budget ran out and the harness cut it
 * short, and ERROR means the harness or engine failed to complete the game at
 * all. Folding any of these into a loss would silently distort every comparison.
 * <p>
 * TIMEOUT is kept separate from DRAW for the same reason CAP is: a game we
 * abandoned because it was slow is not a game that genuinely drew, and how
 * often it happens is itself a number worth watching -- a run that is 30%
 * TIMEOUT is measuring the budget, not the bots.
 *
 * @author Darrell Best
 */
public enum Termination {
    WIN,
    CAP,
    DRAW,
    TIMEOUT,
    ERROR
}
