package org.mage.test.commander;

import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.learn.FederatedWeights;
import mage.player.ai.commander.learn.OnlineTDLearner;
import mage.player.ai.commander.learn.StateFeatures;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

/**
 * DARRELLBEST-FORK: tests for the learning bot's weight plumbing.
 * <p>
 * These are plain unit tests, not games. The learning path is worth testing precisely because its
 * failures are silent: a model that never updates, or updates that get lost at the merge, produce a
 * bot that plays exactly as well as before and looks fine. There is no exception and no log line to
 * notice.
 *
 * @author Darrell Best
 */
public class FederatedLearningTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FederatedWeights weightsIn(String name) {
        Path p = folder.getRoot().toPath().resolve(name);
        return new FederatedWeights(p);
    }

    @Test
    public void missingFileYieldsUsableSeededModel() {
        // DARRELLBEST-FORK: this test used to assert a ZERO model and had been failing ever since
        // checkout() was deliberately changed to seed from the hand-tuned evaluator instead.
        //
        // A zero vector predicts 0.5 for every position, so the search gets the same number for every
        // non-terminal state and plays blind -- which is exactly the failure that motivated seeding.
        // The assertion was therefore pinning the bug, not the behaviour, and its own sibling
        // FederatedWeightsConcurrencyTest.freshModelSeedsFromTunedRatherThanZero asserts the opposite
        // and passes. Two tests in the same suite demanding opposite things is worse than either.
        //
        // Renamed rather than deleted so the intent is visible in the history: a fresh install must
        // start at version 0 AND already know what the tuning established.
        FederatedWeights.Snapshot snap = weightsIn("absent.txt").checkout();
        Assert.assertEquals("a fresh install must start at version 0", 0, snap.version);
        Assert.assertEquals(StateFeatures.SIZE, snap.weights.length);
        Assert.assertArrayEquals("a fresh model IS the hand-tuned evaluator, not a blank slate",
                StateFeatures.seedFromParams(CommanderEvalParams.TUNED), snap.weights, 1e-12);
        boolean anyNonZero = false;
        for (double w : snap.weights) {
            anyNonZero |= w != 0.0;
        }
        Assert.assertTrue("a seeded model must carry information", anyNonZero);
    }

    @Test
    public void mergeIsVisibleToTheNextCheckout() {
        FederatedWeights fed = weightsIn("w.txt");
        double[] delta = new double[StateFeatures.SIZE];
        delta[0] = 1.0; // life_diff

        fed.merge(delta, 0.25, 1000, 0);

        FederatedWeights.Snapshot after = fed.checkout();
        Assert.assertEquals("a merge must advance the version", 1, after.version);
        Assert.assertTrue("life_diff should have moved toward the delta", after.weights[0] > 0.0);
        Assert.assertTrue("but damped, never applied whole", after.weights[0] < 1.0);
        Assert.assertTrue("bias should move too", after.bias > 0.0);
    }

    @Test
    public void mergesAccumulateAcrossGames() {
        FederatedWeights fed = weightsIn("w.txt");
        double[] delta = new double[StateFeatures.SIZE];
        delta[0] = 1.0;

        fed.merge(delta, 0, 1000, 0);
        double afterOne = fed.checkout().weights[0];
        fed.merge(delta, 0, 1000, 1);
        FederatedWeights.Snapshot afterTwo = fed.checkout();

        Assert.assertEquals(2, afterTwo.version);
        Assert.assertTrue("a second game's learning must add to the first, not replace it",
                afterTwo.weights[0] > afterOne);
    }

    @Test
    public void emptyGameContributesNothing() {
        FederatedWeights fed = weightsIn("w.txt");
        // A game that ended before the bot made a decision: null delta, zero updates.
        fed.merge(null, 0, 0, 0);
        Assert.assertEquals("no decisions means no version bump and no dilution",
                0, fed.checkout().version);
    }

    @Test
    public void staleClientIsDampedRelativeToFreshOne() {
        double[] delta = new double[StateFeatures.SIZE];
        delta[0] = 1.0;

        FederatedWeights fresh = weightsIn("fresh.txt");
        fresh.merge(delta, 0, 1000, 0);
        double freshMove = fresh.checkout().weights[0];

        // Same delta, same sample count, but the client checked out 500 versions ago.
        FederatedWeights stale = weightsIn("stale.txt");
        stale.merge(delta, 0, 1000, -500);
        double staleMove = stale.checkout().weights[0];

        Assert.assertTrue("a client that learned against a long-gone model must count for less",
                staleMove < freshMove);
        Assert.assertTrue("but it should still contribute something", staleMove > 0.0);
    }

    @Test
    public void learnerProducesDeltaOnlyAfterRealUpdates() {
        OnlineTDLearner learner = new OnlineTDLearner(new double[StateFeatures.SIZE], 0);
        Assert.assertNull("no observations yet, so nothing to federate", learner.weightDelta());
        Assert.assertEquals(0, learner.updateCount());

        learner.observe(featuresWithLifeDiff(10));
        // One observation establishes a starting state but has nothing to bootstrap from yet.
        Assert.assertNull(learner.weightDelta());

        learner.observe(featuresWithLifeDiff(12));
        Assert.assertNotNull("a transition is a trainable step", learner.weightDelta());
        Assert.assertTrue(learner.updateCount() > 0);
    }

    @Test
    public void winningRaisesThePredictionForStatesThatLedToIt() {
        OnlineTDLearner learner = new OnlineTDLearner(new double[StateFeatures.SIZE], 0);
        double[] state = featuresWithLifeDiff(15);

        double before = learner.predict(state);
        Assert.assertEquals("a zero model knows nothing", 0.5, before, 1e-9);

        // Play the same winning line repeatedly; the value of that state should climb toward 1.
        for (int game = 0; game < 40; game++) {
            OnlineTDLearner g = learner;
            g.observe(state);
            g.observe(featuresWithLifeDiff(20));
            g.finish(true);
        }
        double after = learner.predict(state);
        Assert.assertTrue("states on a winning path must gain value, got " + after,
                after > before);
    }

    @Test
    public void losingLowersThePrediction() {
        OnlineTDLearner learner = new OnlineTDLearner(new double[StateFeatures.SIZE], 0);
        double[] state = featuresWithLifeDiff(-15);

        for (int game = 0; game < 40; game++) {
            learner.observe(state);
            learner.observe(featuresWithLifeDiff(-20));
            learner.finish(false);
        }
        Assert.assertTrue("states on a losing path must lose value", learner.predict(state) < 0.5);
    }

    @Test
    public void predictionStaysFiniteUnderExtremeInput() {
        // Guards the sigmoid clamp: an overflow to NaN would poison every later update silently,
        // because the bot keeps playing and only the quality degrades.
        OnlineTDLearner learner = new OnlineTDLearner(new double[StateFeatures.SIZE], 0);
        double[] absurd = new double[StateFeatures.SIZE];
        java.util.Arrays.fill(absurd, 1e9);
        double p = learner.predict(absurd);
        Assert.assertFalse("prediction must never be NaN", Double.isNaN(p));
        Assert.assertTrue(p >= 0.0 && p <= 1.0);
    }

    private static double[] featuresWithLifeDiff(double lifeDiff) {
        double[] f = new double[StateFeatures.SIZE];
        f[0] = lifeDiff;
        return f;
    }
}
