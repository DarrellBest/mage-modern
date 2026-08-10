package mage.bench;

/**
 * How a benchmark game ended. CAP, DRAW, and ERROR are all deliberately
 * distinct from a loss: CAP means the game hit the turn cap without a
 * winner, DRAW means the game genuinely ended (or stalled) with no winner
 * before the cap was reached, and ERROR means the harness or engine failed
 * to complete the game at all. Folding any of these into a loss would
 * silently distort every comparison.
 *
 * @author Darrell Best
 */
public enum Termination {
    WIN,
    CAP,
    DRAW,
    ERROR
}
