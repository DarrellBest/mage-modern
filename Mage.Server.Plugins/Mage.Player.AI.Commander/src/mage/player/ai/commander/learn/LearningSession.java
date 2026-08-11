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
        federation.merge(learner.weightDelta(), learner.biasDelta(),
                learner.updateCount(), checkout.version);
    }
}
