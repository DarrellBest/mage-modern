package mage.player.ai.commander;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.commander.learn.FederatedWeights;
import mage.player.ai.commander.learn.LearningSession;
import mage.player.ai.commander.learn.StateFeatures;
import mage.player.ai.commander.score.GameStateEvaluator2;

/**
 * DARRELLBEST-FORK: the learning bot. Same search as {@link ComputerPlayerCommander}; the difference
 * is where the number comes from that the search maximises, and that the number improves as it plays.
 * <p>
 * Not a fourth copy of MAD: search and evaluation are separable, this fork already routed every
 * evaluation through {@link ComputerPlayer6#evaluateState}, and duplicating 3.5k more lines to
 * change one method would mean every future search fix had to be made twice.
 * <p>
 * <b>Lifecycle.</b> {@link #init} checks out the shared weights and opens a private
 * {@link LearningSession}; {@link #priority} records each state the player really reached;
 * {@link #won}/{@link #lost} back up the true result and federate the delta. Every one of those is
 * gated on {@code !game.isSimulation()}.
 * <p>
 * <b>That gate is the correctness crux.</b> The search evaluates thousands of hypothetical futures
 * per turn through copies of this player. Training on them would teach the model that positions it
 * merely considered are positions it experienced -- and since the search preferentially explores
 * positions it already rates highly, the model would be trained mostly on states its own current
 * weights liked, reinforcing whatever it already believed. That is a feedback loop that looks like
 * learning and is actually drift.
 * <p>
 * <b>Terminal states never go through the model.</b> A learned scorer is a regression over ordinary
 * positions; asked about a won game it returns some large-ish number rather than "won". The search
 * needs winning states to outscore every non-winning state absolutely, so wins and losses are still
 * answered by the hand-tuned evaluator's +/-100_000_000. Without this the bot would trade a real win
 * for a position the model merely likes.
 * <p>
 * <b>With no weights file it plays exactly like {@link ComputerPlayerCommander}</b>, which is also
 * the state at version 0 (a zero weight vector predicts 0.5 everywhere and carries no information).
 * That makes "did learning help?" a clean comparison: same search, same decks, one evaluator swapped.
 *
 * @author Darrell Best
 */
public class ComputerPlayerLearner extends ComputerPlayer7 {

    private static final org.apache.log4j.Logger LOG =
            org.apache.log4j.Logger.getLogger(ComputerPlayerLearner.class);

    private final FederatedWeights federation;

    /**
     * Shared by reference with every copy of this player -- see {@link LearningSession} for why the
     * copies must share rather than each hold their own.
     */
    private LearningSession session;

    public ComputerPlayerLearner(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        this.federation = new FederatedWeights();
    }

    public ComputerPlayerLearner(final ComputerPlayerLearner player) {
        super(player);
        this.federation = player.federation;
        this.session = player.session;
    }

    @Override
    public ComputerPlayerLearner copy() {
        return new ComputerPlayerLearner(this);
    }

    /**
     * DARRELLBEST-FORK: the session is opened lazily here, NOT in {@code init(Game)}.
     * <p>
     * {@code Player.init(Game)} exists on the interface but the engine never calls it:
     * {@code GameImpl.init} calls {@code player.beginTurn(this)} instead. An {@code init} override
     * therefore never ran, the session stayed null, and every learning call silently no-opped -- the
     * bot played normally, wrote no weights, and reported no error. Four real games produced no
     * weights file at all before this was found, and the unit tests could not have caught it because
     * they never involve a Game. Creating the session on first real priority binds it to a callback
     * the engine demonstrably makes.
     */
    private void ensureSession(Game game) {
        if (session == null && !game.isSimulation()) {
            session = new LearningSession(federation);
        }
    }

    @Override
    public boolean priority(Game game) {
        // Observe BEFORE deciding: the state being recorded is the one this player faced, not the
        // one its own action produced. Recording after the decision would train the model on states
        // that already include the benefit of the move, which is the outcome, not the input.
        if (!game.isSimulation()) {
            ensureSession(game);
            session.learner().observe(StateFeatures.extract(playerId, game));
        }
        return super.priority(game);
    }

    @Override
    public void won(Game game) {
        super.won(game);
        if (session != null && !game.isSimulation()) {
            session.finishOnce(true);
        }
    }

    @Override
    public void lost(Game game) {
        super.lost(game);
        if (session != null && !game.isSimulation()) {
            session.finishOnce(false);
        }
    }

    @Override
    protected int evaluateState(Game game) {
        LearningSession current = session;
        if (current == null) {
            return super.evaluateState(game);
        }
        if (game.checkIfGameIsOver()) {
            // Ask the same question the search itself uses to stop descending, rather than a second
            // one that might disagree with it.
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        double[] features = StateFeatures.extract(playerId, game);
        if (features == null) {
            return super.evaluateState(game);
        }
        int handTuned = super.evaluateState(game);
        double trust = current.learnedWeight();
        if (trust <= 0.0) {
            // Untrained model: it would return the same number for every position and blind the
            // search. See LearningSession.learnedWeight for the measurement behind this.
            return handTuned;
        }
        double p = current.learner().predict(features);
        // Map a win probability onto GameStateEvaluator2's scale, well inside its terminal values so
        // a merely good position can never outscore an actual win.
        int learned = (int) ((p - 0.5) * 1_000_000);
        return (int) ((1.0 - trust) * handTuned + trust * learned);
    }
}
