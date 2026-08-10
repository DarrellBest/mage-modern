package mage.bench;

/**
 * How a benchmark game ended. CAP and ERROR are deliberately distinct from a
 * loss: "never finished" and "lost" mean very different things, and folding
 * them together would silently distort every comparison.
 *
 * @author Darrell Best
 */
public enum Termination {
    WIN,
    CAP,
    ERROR
}
