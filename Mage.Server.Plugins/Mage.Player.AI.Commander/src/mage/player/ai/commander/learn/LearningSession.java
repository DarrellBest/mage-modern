package mage.player.ai.commander.learn;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DARRELLBEST-FORK: one game's learning state, held by reference so every copy of the player shares
 * it.
 * <p>
 * XMage copies a player object freely -- once per simulated branch, and potentially when the real
 * game starts. That makes "which object is the real player?" an unreliable thing to depend on, so
 * this class deliberately does NOT try to answer it. Instead every copy points at the same session,
 * and the calls that mutate it are gated on {@code !game.isSimulation()}, which is a property of the
 * game rather than of the object. Whichever copy ends up receiving real priority does the learning;
 * simulated branches cannot, no matter how many copies exist.
 * <p>
 * The alternative -- give copies a null learner so they physically cannot train -- was rejected
 * because if the engine copies the player when the real game starts, the copy that actually plays
 * would be the one unable to learn, and the bot would silently never train at all.
 *
 * @author Darrell Best
 */
public final class LearningSession {

    private final FederatedWeights federation;
    private final FederatedWeights.Snapshot checkout;
    private final OnlineTDLearner learner;
    private final AtomicBoolean merged = new AtomicBoolean(false);

    public LearningSession(FederatedWeights federation) {
        this.federation = federation;
        this.checkout = federation.checkout();
        this.learner = new OnlineTDLearner(checkout.weights, checkout.bias);
    }

    public OnlineTDLearner learner() {
        return learner;
    }

    public FederatedWeights.Snapshot checkout() {
        return checkout;
    }

    /**
     * How much to trust the learned evaluation, from 0 (not at all) to 1 (entirely).
     * <p>
     * DARRELLBEST-FORK: this exists because the bot could not otherwise bootstrap. A zero weight
     * vector predicts 0.5 for every position, so a scorer built from it returns the SAME number for
     * every non-terminal state and the search becomes blind -- it cannot tell a winning board from a
     * losing one. Measured: the learner ran Kairi vs Ur-Dragon to the 30-turn cap in 2/2 games, a
     * matchup the same search decides in 9-24 turns with the hand-tuned evaluator. Nobody won, so
     * won()/lost() never fired, so nothing federated, so the model stayed zero -- blind play prevents
     * exactly the decisive games the model needs in order to stop being blind.
     * <p>
     * Ramping trust with version breaks that loop: early games are played at full hand-tuned strength
     * and produce real outcomes to learn from, and the learned evaluation takes over only as evidence
     * accumulates. FULL_TRUST_VERSION is a guess, not a measured optimum -- the right value is
     * whenever the learned evaluator starts beating the hand-tuned one head to head, which is a
     * benchmark question.
     */
    public double learnedWeight() {
        return Math.min(1.0, checkout.version / (double) FULL_TRUST_VERSION);
    }

    /** Federated merges after which the learned evaluation is trusted completely. */
    private static final int FULL_TRUST_VERSION = 500;

    /**
     * Back up the result and federate. Safe to call more than once and from more than one copy:
     * only the first call does anything.
     * <p>
     * Idempotence is required, not defensive. {@code won()} and {@code lost()} can both fire for the
     * same player as a game resolves, and several copies may each see the end of the game. Merging
     * twice would apply one game's learning to the shared model twice, over-weighting it against
     * every other instance's contribution.
     */
    public void finishOnce(boolean won) {
        if (!merged.compareAndSet(false, true)) {
            return;
        }
        learner.finish(won);
        int updates = learner.updateCount();
        federation.merge(learner.weightDelta(), learner.biasDelta(), updates, checkout.version);
        // Logged so a silently-not-learning bot is visible without attaching a debugger. The
        // previous failure mode -- session never created, every learning call a no-op -- produced a
        // bot that played normally and wrote nothing, with no error anywhere.
        org.apache.log4j.Logger.getLogger(LearningSession.class).info(
                "Commander learner federated after game: won=" + won + " updates=" + updates
                        + " fromVersion=" + checkout.version);
    }
}
