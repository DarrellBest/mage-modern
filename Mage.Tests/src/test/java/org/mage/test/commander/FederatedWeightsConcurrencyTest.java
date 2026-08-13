package org.mage.test.commander;

import mage.player.ai.commander.learn.FederatedWeights;
import mage.player.ai.commander.learn.StateFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DARRELLBEST-FORK: PROOF that concurrent games cannot lose each other's learning.
 * <p>
 * The bug this guards against was live on the server and completely silent. {@code FileLock} is
 * per-JVM, not per-thread: a second thread in the same process asking for a lock that process
 * already holds gets {@link java.nio.channels.OverlappingFileLockException}, not a wait. The merge
 * path caught that, logged it, and dropped the update -- so a game's entire learning vanished with
 * no error anyone would notice.
 * <p>
 * Separate bench processes were never affected, because cross-process is exactly what FileLock is
 * for. The live server was the broken case: every game runs in ONE JVM, so any two games finishing
 * near each other collided. {@code synchronized} on the merge method did not help, because it was
 * per-INSTANCE and every {@code ComputerPlayerLearner} constructs its own {@code FederatedWeights}.
 * <p>
 * Measured before the fix: 8 threads x 40 merges = 320 attempted, <b>41 landed, 279 lost</b>.
 * <p>
 * The version counter is the assertion because it increments once per successful merge, so a lost
 * update shows up as a version below the number attempted. Checking weights instead would be weaker
 * -- FedAvg damping makes individual contributions hard to attribute, and a dropped merge could hide
 * inside rounding.
 */
public class FederatedWeightsConcurrencyTest {

    private static final int THREADS = 8;
    private static final int MERGES_PER_THREAD = 25;

    @Test
    public void concurrentMergesInOneJvmAllLand() throws Exception {
        Path file = Files.createTempFile("commander-weights-concurrency", ".txt");
        Files.deleteIfExists(file);
        try {
            Thread[] threads = new Thread[THREADS];
            for (int t = 0; t < THREADS; t++) {
                threads[t] = new Thread(() -> {
                    // a separate instance per thread, exactly like separate games on the server
                    FederatedWeights weights = new FederatedWeights(file);
                    for (int i = 0; i < MERGES_PER_THREAD; i++) {
                        double[] delta = new double[StateFeatures.SIZE];
                        delta[0] = 1.0;
                        weights.merge(delta, 0.0, 1000, weights.checkout().version);
                    }
                });
            }
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join(120_000);
            }

            long version = versionOf(file);
            Assert.assertEquals(
                    "every concurrent merge must land -- a lower version means updates were lost to "
                            + "OverlappingFileLockException, which the merge path swallows silently",
                    (long) THREADS * MERGES_PER_THREAD, version);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A fresh model must come back seeded from the tuned bot, never as a zero vector. */
    @Test
    public void freshModelSeedsFromTunedRatherThanZero() throws Exception {
        Path file = Files.createTempFile("commander-weights-seed", ".txt");
        Files.deleteIfExists(file);
        try {
            double[] w = new FederatedWeights(file).checkout().weights;
            int nonZero = 0;
            for (double v : w) {
                if (v != 0.0) {
                    nonZero++;
                }
            }
            Assert.assertTrue("a fresh model must start at the hand-tuned weights, not at zero -- "
                    + "a zero start makes the learner rediscover everything the tuning established",
                    nonZero > 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static long versionOf(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            if (line.startsWith("version=")) {
                return (long) Double.parseDouble(line.substring("version=".length()).trim());
            }
        }
        return -1;
    }
}
