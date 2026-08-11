package mage.player.ai.commander.learn;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * DARRELLBEST-FORK: the federation point. Every instance checks out the global weights when its game
 * starts, learns privately ({@link OnlineTDLearner}), and merges its delta back when the game ends.
 * <p>
 * The merge is FedAvg: the global model moves toward each client's delta in proportion to how much
 * experience that client contributed. A 40-turn game with 300 decisions should move the model more
 * than a 6-turn blowout with 20, which is why {@link OnlineTDLearner#updateCount()} is the weight
 * rather than each finished game counting once.
 * <p>
 * <b>Concurrency is between PROCESSES, not just threads.</b> Bench runs, the live server, and any
 * self-play job are separate JVMs sharing one weights file, so a Java lock would not help. Each
 * merge takes an OS-level {@link FileLock} for a read-modify-write. Without it, two games ending at
 * the same moment both read version N, both write N+1, and one game's learning silently disappears
 * -- a bug that looks like "learning is slower than expected" rather than like a fault.
 * <p>
 * <b>Deltas, not absolute weights.</b> A client that checked out an old version and merges its
 * absolute weights would undo everything learned since it started. Merging the delta applies only
 * what that client actually learned, so a long game federating against a model that moved underneath
 * it still contributes correctly. This is the same reason distributed training exchanges gradients.
 * <p>
 * <b>Staleness is bounded by damping.</b> A very stale client (checked out hundreds of versions ago)
 * has learned against a model that no longer exists, so its delta is applied at reduced strength.
 *
 * @author Darrell Best
 */
public final class FederatedWeights {

    private static final Logger logger = Logger.getLogger(FederatedWeights.class);

    public static final String PATH_PROPERTY = "xmage.commander.weights";
    public static final String DEFAULT_PATH = "commander-weights.txt";

    /** Cap on a single game's influence, so one anomalous game cannot swing the shared model. */
    private static final double MAX_MERGE_FRACTION = 0.10;

    /** Updates at which a client's delta gets full weight; below this it counts proportionally. */
    private static final int FULL_WEIGHT_UPDATES = 200;

    private final Path path;

    public FederatedWeights() {
        this(Paths.get(System.getProperty(PATH_PROPERTY, DEFAULT_PATH)));
    }

    public FederatedWeights(Path path) {
        this.path = path;
    }

    /** Immutable snapshot handed to a game at its start. */
    public static final class Snapshot {
        public final double[] weights;
        public final double bias;
        public final long version;

        Snapshot(double[] weights, double bias, long version) {
            this.weights = weights;
            this.bias = bias;
            this.version = version;
        }
    }

    /**
     * @return the current global model, or a zero model at version 0 when no file exists yet. A zero
     *         model predicts 0.5 everywhere, which is useless but harmless -- callers should keep
     *         using the hand-tuned evaluator until the model has learned something.
     */
    public synchronized Snapshot checkout() {
        double[] w = new double[StateFeatures.SIZE];
        double bias = 0;
        long version = 0;
        if (!Files.exists(path)) {
            return new Snapshot(w, bias, version);
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
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
                    bias = value;
                } else if ("version".equals(key)) {
                    version = (long) value;
                } else {
                    int idx = indexOf(key);
                    if (idx >= 0) {
                        w[idx] = value;
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            logger.warn("Federated weights unreadable, starting from zero: " + e);
            return new Snapshot(new double[StateFeatures.SIZE], 0, 0);
        }
        return new Snapshot(w, bias, version);
    }

    /**
     * Merge one finished game's learning into the global model under an exclusive file lock.
     *
     * @param delta        per-weight change this game produced
     * @param biasDelta    change to the bias term
     * @param updateCount  how many TD updates produced that delta; the client's sample weight
     * @param checkoutVersion the version this client started from, for staleness damping
     */
    public synchronized void merge(double[] delta, double biasDelta, int updateCount, long checkoutVersion) {
        if (delta == null || updateCount <= 0 || delta.length != StateFeatures.SIZE) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock()) {

            Snapshot current = readLocked(raf);
            double fraction = MAX_MERGE_FRACTION
                    * Math.min(1.0, updateCount / (double) FULL_WEIGHT_UPDATES)
                    * stalenessDamping(current.version, checkoutVersion);

            double[] merged = new double[StateFeatures.SIZE];
            for (int i = 0; i < merged.length; i++) {
                merged[i] = current.weights[i] + fraction * delta[i];
            }
            double mergedBias = current.bias + fraction * biasDelta;
            writeLocked(raf, merged, mergedBias, current.version + 1);
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            // A failed merge loses one game's learning; it must never take the game down with it.
            logger.warn("Federated merge failed (one game's learning discarded): " + e);
        }
    }

    private static double stalenessDamping(long currentVersion, long checkoutVersion) {
        long behind = Math.max(0, currentVersion - checkoutVersion);
        return 1.0 / (1.0 + behind / 50.0);
    }

    private Snapshot readLocked(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        double[] w = new double[StateFeatures.SIZE];
        double bias = 0;
        long version = 0;
        String line;
        while ((line = raf.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            try {
                String key = line.substring(0, eq).trim();
                double value = Double.parseDouble(line.substring(eq + 1).trim());
                if ("bias".equals(key)) {
                    bias = value;
                } else if ("version".equals(key)) {
                    version = (long) value;
                } else {
                    int idx = indexOf(key);
                    if (idx >= 0) {
                        w[idx] = value;
                    }
                }
            } catch (NumberFormatException ignored) {
                // skip a corrupt line rather than abandoning the whole merge
            }
        }
        return new Snapshot(w, bias, version);
    }

    private void writeLocked(RandomAccessFile raf, double[] weights, double bias, long version) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# commander bot federated weights -- written by FederatedWeights.merge");
        out.add("version=" + version);
        out.add("bias=" + bias);
        for (int i = 0; i < weights.length; i++) {
            out.add(StateFeatures.NAMES[i] + "=" + weights[i]);
        }
        byte[] bytes = String.join("\n", out).concat("\n").getBytes(StandardCharsets.UTF_8);
        raf.seek(0);
        raf.write(bytes);
        // The new content can be shorter than the old; without truncating, the tail of the previous
        // version survives and parses as duplicate keys.
        raf.setLength(bytes.length);
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
