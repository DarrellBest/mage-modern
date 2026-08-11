package mage.player.ai.commander.learn;

import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * DARRELLBEST-FORK: the learned half of the learning bot -- a weight vector over
 * {@link StateFeatures}, loaded from a file that a training run writes.
 * <p>
 * <b>Why a file and not in-process online learning.</b> The requirement was that every instance of
 * the bot shares one improving set of weights. A file gives that: training runs offline against
 * recorded games, writes a new version, and every instance picks it up on its next game. The
 * alternative -- mutating weights inside the game thread as games play -- would have several game
 * threads writing a shared array while the search reads it, and one bad gradient step would degrade
 * every game in progress simultaneously with no way to roll back. Collect continuously, train
 * offline, swap atomically: same outcome, none of that.
 * <p>
 * <b>Reload is per-game, never mid-search.</b> {@link #reloadIfChanged()} is called when a game
 * starts. A minimax search compares scores from different positions against each other, so if the
 * weights changed halfway through, the second half of the tree would be scored by a different
 * function than the first and the comparison would be meaningless.
 * <p>
 * Missing or malformed weights are not an error: {@link #isReady()} returns false and the caller
 * falls back to the hand-tuned evaluator. A bot that plays like MAD is a fine default; a bot that
 * refuses to start because a training artifact is absent is not.
 *
 * @author Darrell Best
 */
public final class LinearEvaluator {

    private static final Logger logger = Logger.getLogger(LinearEvaluator.class);

    /** Override with -Dxmage.commander.weights=/path/to/weights.txt */
    public static final String WEIGHTS_PROPERTY = "xmage.commander.weights";
    public static final String DEFAULT_WEIGHTS_PATH = "commander-weights.txt";

    /**
     * The learned model predicts a win probability in [0,1]; the search expects scores on
     * GameStateEvaluator2's scale, where terminal states are +/-100_000_000 and a point of life is
     * 300. Mapping p to (p - 0.5) * SCORE_SCALE keeps ordinary positions comfortably inside the
     * terminal values, so a merely good position can never outscore an actual win.
     */
    public static final int SCORE_SCALE = 1_000_000;

    private final Path path;
    private double[] weights;
    private double bias;
    private long loadedMtime = -1;

    public LinearEvaluator() {
        this(Paths.get(System.getProperty(WEIGHTS_PROPERTY, DEFAULT_WEIGHTS_PATH)));
    }

    public LinearEvaluator(Path path) {
        this.path = path;
        reloadIfChanged();
    }

    public boolean isReady() {
        return weights != null;
    }

    /**
     * Load the weight file if it appeared or changed since the last load. Call between games only.
     */
    public synchronized void reloadIfChanged() {
        try {
            if (!Files.exists(path)) {
                return;
            }
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == loadedMtime) {
                return;
            }
            double[] parsed = new double[StateFeatures.SIZE];
            double parsedBias = 0;
            int seen = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(path.toFile()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = line.substring(0, eq).trim();
                    double value = Double.parseDouble(line.substring(eq + 1).trim());
                    if ("bias".equals(key)) {
                        parsedBias = value;
                        continue;
                    }
                    int idx = indexOf(key);
                    if (idx < 0) {
                        // A name this build does not know: the file was trained against a different
                        // feature set. Refuse the whole file rather than silently scoring with a
                        // vector where some positions mean something else than they did in training.
                        logger.warn("Commander weights rejected: unknown feature '" + key + "' in " + path);
                        return;
                    }
                    parsed[idx] = value;
                    seen++;
                }
            }
            if (seen != StateFeatures.SIZE) {
                logger.warn("Commander weights rejected: " + path + " has " + seen
                        + " features, this build expects " + StateFeatures.SIZE);
                return;
            }
            this.weights = parsed;
            this.bias = parsedBias;
            this.loadedMtime = mtime;
            logger.info("Commander bot loaded learned weights from " + path);
        } catch (IOException | NumberFormatException e) {
            logger.warn("Commander weights unreadable (" + path + "), falling back to hand-tuned evaluator: " + e);
        }
    }

    /**
     * @return score on GameStateEvaluator2's scale, or null when no weights are loaded
     */
    public Integer score(double[] features) {
        double[] w = this.weights;
        if (w == null || features == null || features.length != w.length) {
            return null;
        }
        double z = bias;
        for (int i = 0; i < w.length; i++) {
            z += w[i] * features[i];
        }
        double p = 1.0 / (1.0 + Math.exp(-z));
        return (int) ((p - 0.5) * SCORE_SCALE);
    }

    private static int indexOf(String name) {
        for (int i = 0; i < StateFeatures.NAMES.length; i++) {
            if (StateFeatures.NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
