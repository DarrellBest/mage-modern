package mage.player.ai.commander.learn;

/**
 * DARRELLBEST-FORK: per-instance online learner. Holds a PRIVATE copy of the weights, updates them
 * as its own game plays out, and hands the accumulated delta to {@link FederatedWeights} at game end.
 * <p>
 * <b>Private copy is the whole point.</b> Nothing here is shared with another game, so there is no
 * lock, no race, and no way for one instance's bad step to affect a game in progress elsewhere. That
 * was the real objection to "live training"; federating at a synchronisation point answers it.
 * <p>
 * <b>Why TD and not supervised updates.</b> Mid-game there is no label -- the game has not been won
 * or lost yet. TD(lambda) bootstraps instead: the target for state s(t) is the learner's own
 * evaluation of s(t+1), so a prediction is trained toward the next, better-informed prediction. At
 * game end the real result replaces the bootstrap and is backed up through the eligibility trace, so
 * the terminal truth propagates to every state that led to it. Without this the bot could only learn
 * from completed games, which is the batch design federation was meant to replace.
 * <p>
 * <b>Updates never happen inside a search.</b> {@link #observe} is called between decisions, never
 * from {@code evaluateState}. A minimax search compares scores across branches; if the weights moved
 * partway through, the second half of the tree would be scored by a different function than the
 * first and the comparison would be meaningless. Same reason weights are checked out once per game
 * rather than re-read during one.
 * <p>
 * <b>Features are normalised here, unlike the offline path.</b> {@link StateFeatures} emits raw
 * counts on wildly different scales (life_diff spans about +/-40, planeswalker_count_diff about
 * +/-2). Offline training with regularisation copes; online SGD does not -- the large-magnitude
 * features dominate the gradient and the weight vector diverges within a few hundred steps. Dividing
 * by a per-feature scale puts every input in roughly [-1, 1] so one learning rate suits all of them.
 * <p>
 * <b>The scales are NOT owned here.</b> They used to be: a {@code private static final double[]
 * SCALE} that had to stay index-aligned with {@link StateFeatures#NAMES} by convention. Three
 * features were once appended to NAMES and not to SCALE, {@code logit()} threw
 * ArrayIndexOutOfBoundsException on the first evaluation of every game, the callers swallowed it,
 * and three games "completed" having learned nothing. A length assertion was added, which turned
 * silent breakage into loud breakage but left the two arrays needing to be edited together.
 * They are now one table in {@link StateFeatures}, read through {@link StateFeatures#scales()}, so
 * there is nothing left to keep in sync.
 * <p>
 * Read once into a field at construction rather than per call: the scales cannot change during a
 * game (they are a property of the feature definition, not the state), and {@link #predict} runs at
 * every search leaf.
 *
 * @author Darrell Best
 */
public final class OnlineTDLearner {

    private static final double DEFAULT_LEARNING_RATE = 0.01;
    private static final double DEFAULT_LAMBDA = 0.7;

    private final double learningRate;
    private final double lambda;

    private final double[] scale;        // per-feature normalisers, owned by StateFeatures
    private final double[] weights;      // private working copy
    private final double[] baseline;     // what we checked out, so the delta can be computed
    private final double[] trace;        // eligibility trace, one per weight
    private double bias;
    private final double baselineBias;
    private double biasTrace;

    private double[] lastFeatures;
    private int updates;

    public OnlineTDLearner(double[] initialWeights, double initialBias) {
        this(initialWeights, initialBias, DEFAULT_LEARNING_RATE, DEFAULT_LAMBDA);
    }

    public OnlineTDLearner(double[] initialWeights, double initialBias, double learningRate, double lambda) {
        this.scale = StateFeatures.scales();
        if (initialWeights.length != this.scale.length) {
            // A weight vector of the wrong length would have run off the end of the scales inside
            // logit(), which is the crash this class used to produce whenever the two feature lists
            // disagreed. There is no sensible partial answer, so refuse at construction where the
            // stack trace still names the caller that supplied the bad vector.
            throw new IllegalArgumentException("weight vector has " + initialWeights.length
                    + " entries but StateFeatures defines " + this.scale.length + " features");
        }
        this.weights = initialWeights.clone();
        this.baseline = initialWeights.clone();
        this.trace = new double[initialWeights.length];
        this.bias = initialBias;
        this.baselineBias = initialBias;
        this.learningRate = learningRate;
        this.lambda = lambda;
    }

    /** Predicted win probability for a feature vector, in [0,1]. */
    public double predict(double[] features) {
        return sigmoid(logit(features));
    }

    /**
     * Record one state transition. Call between decisions, once per state the real player actually
     * reached -- never for simulated states, which are hypotheticals the game never visited and
     * would teach the model that positions it merely considered are positions it experienced.
     */
    public void observe(double[] features) {
        if (features == null || features.length != weights.length) {
            return;
        }
        if (lastFeatures != null) {
            // TD target is the current state's own prediction: bootstrapping.
            applyUpdate(lastFeatures, predict(features));
        }
        lastFeatures = features.clone();
    }

    /**
     * Back up the real result through the trace. Call once, at game end.
     *
     * @param won true if this player won the game
     */
    public void finish(boolean won) {
        if (lastFeatures != null) {
            applyUpdate(lastFeatures, won ? 1.0 : 0.0);
            lastFeatures = null;
        }
    }

    /**
     * @return the change this game made to each weight, for federated averaging. Null when the game
     *         produced no updates, so a game that ended before any decision contributes nothing
     *         rather than contributing a zero vector that would dilute everyone else's learning.
     */
    public double[] weightDelta() {
        if (updates == 0) {
            return null;
        }
        double[] delta = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            delta[i] = weights[i] - baseline[i];
        }
        return delta;
    }

    public double biasDelta() {
        return bias - baselineBias;
    }

    /** Number of TD updates this game produced; the sample weight for federated averaging. */
    public int updateCount() {
        return updates;
    }

    private void applyUpdate(double[] features, double target) {
        double prediction = sigmoid(logit(features));
        double error = target - prediction;
        for (int i = 0; i < weights.length; i++) {
            trace[i] = lambda * trace[i] + features[i] / scale[i];
            weights[i] += learningRate * error * trace[i];
        }
        biasTrace = lambda * biasTrace + 1.0;
        bias += learningRate * error * biasTrace;
        updates++;
    }

    /**
     * The model. Note the {@code / scale[i]}: it is why {@link StateFeatures#seedFromParams} has to
     * multiply the scale back in, and why a seed that did not was silently a different model.
     */
    private double logit(double[] features) {
        double z = bias;
        for (int i = 0; i < weights.length; i++) {
            z += weights[i] * features[i] / scale[i];
        }
        return z;
    }

    private static double sigmoid(double z) {
        // Guard the exponent: an unbounded z overflows to NaN and silently poisons every later
        // update, which is hard to notice because the bot keeps playing, just badly.
        double clamped = Math.max(-40.0, Math.min(40.0, z));
        return 1.0 / (1.0 + Math.exp(-clamped));
    }
}
