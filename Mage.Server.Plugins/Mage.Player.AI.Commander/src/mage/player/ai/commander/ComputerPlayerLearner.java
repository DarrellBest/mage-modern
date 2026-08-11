package mage.player.ai.commander;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.commander.learn.LinearEvaluator;
import mage.player.ai.commander.learn.StateFeatures;
import mage.player.ai.commander.score.GameStateEvaluator2;

/**
 * DARRELLBEST-FORK: the learning bot. Identical search to {@link ComputerPlayerCommander}; the only
 * difference is where the number comes from that the search maximises.
 * <p>
 * It is deliberately not a fourth copy of MAD. Search and evaluation are separable, this fork
 * already hoisted every evaluation into {@link ComputerPlayer6#evaluateState}, and copying 3.5k more
 * lines to change one method would mean every future search fix had to be made twice.
 * <p>
 * <b>Terminal states never go through the model.</b> A learned scorer is a regression over ordinary
 * positions; asked about a won game it returns some large-ish number rather than "won". The search
 * relies on winning states outscoring every non-winning state absolutely, so wins and losses are
 * still answered by the hand-tuned evaluator, which returns +/-100_000_000 for them. Without this
 * the bot would happily trade a win for a position the model merely likes.
 * <p>
 * <b>With no weights file it plays exactly like {@link ComputerPlayerCommander}.</b> That is the
 * intended state before the first training run, and it makes "did learning help?" a clean
 * comparison: same search, same decks, one evaluator swapped.
 *
 * @author Darrell Best
 */
public class ComputerPlayerLearner extends ComputerPlayer7 {

    private final LinearEvaluator evaluator;

    public ComputerPlayerLearner(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        this.evaluator = new LinearEvaluator();
    }

    public ComputerPlayerLearner(final ComputerPlayerLearner player) {
        super(player);
        // Share the evaluator with the copy rather than re-reading the weights file. Players are
        // copied per simulated branch, and re-loading weights thousands of times per turn would
        // dominate the search cost; worse, a file written mid-search would make different branches
        // score under different weights.
        this.evaluator = player.evaluator;
    }

    @Override
    public ComputerPlayerLearner copy() {
        return new ComputerPlayerLearner(this);
    }

    @Override
    protected int evaluateState(Game game) {
        if (!evaluator.isReady()) {
            return super.evaluateState(game);
        }
        // Terminal states keep the hand-tuned answer -- see the class comment. checkIfGameIsOver()
        // is what the search itself uses to stop descending, so this asks the same question the
        // search asks rather than a second, possibly-disagreeing one.
        if (game.checkIfGameIsOver()) {
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        double[] features = StateFeatures.extract(playerId, game);
        Integer learned = evaluator.score(features);
        return learned != null ? learned : super.evaluateState(game);
    }
}
