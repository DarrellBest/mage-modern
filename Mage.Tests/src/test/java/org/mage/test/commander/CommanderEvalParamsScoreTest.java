package org.mage.test.commander;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import mage.player.ai.commander.score.ArtificialScoringSystem;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.score.GameStateEvaluator2;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

import java.util.ArrayList;
import java.util.List;

/**
 * DARRELLBEST-FORK: pins the commander evaluator's OUTPUT, on a fixed board, to the exact numbers the
 * pre-refactor hard-coded weights produced.
 * <p>
 * <b>Why this test and not a benchmark.</b> The obvious way to check "the bot still plays the same"
 * is to run the bench with a fixed seed before and after. That cannot work here:
 * {@code mage.util.RandomUtil} is one process-wide {@code Random} shared between the game thread and
 * the AI's simulation pool thread, so {@code setSeed} fixes the shuffle and nothing else, and the
 * split of the random stream depends on the scheduler. Measured on THIS build with no code change at
 * all, seeds 12345-12348 produced 19/12/13/15 turns in one run and 11/17/12/7 in the next, with the
 * winning seat flipping in two of the four games. A comparison whose null hypothesis already fails
 * cannot be evidence of anything.
 * <p>
 * {@link GameStateEvaluator2} and {@link ArtificialScoringSystem}, by contrast, are pure functions of
 * a game state: no threads, no clock, no scheduler. Fix the board and the score is a constant. That
 * makes this the right instrument for "the defaults reproduce today's behaviour exactly", and it
 * localises any drift to the exact weight that moved.
 * <p>
 * <b>Where the expected numbers come from.</b> They are computed by hand from the ORIGINAL
 * hard-coded literals (the ones that used to live in {@code ArtificialScoringSystem}), not read off
 * the new implementation -- a number harvested from the code under test would pin nothing. Each
 * assertion below shows the arithmetic.
 *
 * @author Darrell Best
 */
public class CommanderEvalParamsScoreTest extends CardTestPlayerBaseWithAIHelps {

    private static final CommanderEvalParams DEFAULTS = CommanderEvalParams.DEFAULT;

    /**
     * A vanilla 2/2 for {1}{G}, scored with combat terms off so the result cannot depend on phase,
     * summoning sickness or tap state.
     * <p>
     * Old formula, literal by literal:
     * <pre>
     *   getCardDefinitionScore  = 3 * 100 - manaValue(2) * 20      = 260   (non-land branch)
     *                           + (power 2 + toughness 2) * 10     = +40   (creature branch)
     *                                                              = 300
     *   getFixedPermanentScore  = 300 + PERMANENT_SCORE 300        = 600
     *   getDynamicPermanentScore= power 2 * 300                    = 600
     *                           + positive(toughness 2) * 200      = +400
     *                           + abilityScore 2 * (2 + 1) / 2     = +3    (integer division: 6/2)
     *                                                              = 1003
     *   evaluatePermanent       = 0 attachments + 600 + 1003       = 1603
     * </pre>
     * The only quantity above not taken from the old literals is {@code abilityScore}, which comes
     * from {@code MagicAbility.getAbilityScore} in shared upstream code this refactor does not touch.
     * It is 2 for this card, not the 0 a "vanilla" creature suggests -- an assumption this test
     * caught when it was first written, which is a fair advertisement for pinning real numbers rather
     * than trusting a mental model of them.
     */
    @Test
    public void vanillaCreatureScoresExactlyAsBefore() {
        addCard(Zone.BATTLEFIELD, playerA, "Balduvian Bears", 1); // 2/2 for {1}{G}, no abilities

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        Permanent bears = getPermanent("Balduvian Bears", playerA);
        Assert.assertEquals("card definition score",
                300, ArtificialScoringSystem.getCardDefinitionScore(currentGame, bears, DEFAULTS));
        Assert.assertEquals("fixed permanent score",
                600, ArtificialScoringSystem.getFixedPermanentScore(currentGame, bears, DEFAULTS));
        Assert.assertEquals("dynamic permanent score",
                1003, ArtificialScoringSystem.getDynamicPermanentScore(currentGame, bears, DEFAULTS));
        Assert.assertEquals("evaluatePermanent without combat terms",
                1603, GameStateEvaluator2.evaluatePermanent(bears, currentGame, false, DEFAULTS));
    }

    /**
     * A basic land, which takes the other branch of {@code getCardDefinitionScore} -- the one whose
     * float arithmetic is the easiest thing in this refactor to get subtly wrong.
     * <pre>
     *   getCardDefinitionScore  = (int) ((3 / 2.0f) * 50)          = 75    (land branch, float!)
     *                           + getMana().size() 1 * 50          = +50
     *                                                              = 125
     *   getFixedPermanentScore  = 125 + PERMANENT_SCORE 300        = 425   (not a creature, not equipment)
     *   getDynamicPermanentScore= 0 charge + 0 level - 0 damage    = 0     (not a creature)
     *   evaluatePermanent       = 0 attachments + 425 + 0          = 425
     * </pre>
     */
    @Test
    public void basicLandScoresExactlyAsBefore() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        Permanent forest = getPermanent("Forest", playerA);
        Assert.assertEquals("land card definition score (the (int)((3/2.0f)*50) term is 75, not 74 or 76)",
                125, ArtificialScoringSystem.getCardDefinitionScore(currentGame, forest, DEFAULTS));
        Assert.assertEquals("fixed permanent score",
                425, ArtificialScoringSystem.getFixedPermanentScore(currentGame, forest, DEFAULTS));
        Assert.assertEquals("dynamic permanent score",
                0, ArtificialScoringSystem.getDynamicPermanentScore(currentGame, forest, DEFAULTS));
        Assert.assertEquals("evaluatePermanent without combat terms",
                425, GameStateEvaluator2.evaluatePermanent(forest, currentGame, false, DEFAULTS));
    }

    /**
     * The whole-state entry point, including the hand term, on a board with nothing on it.
     * <p>
     * Both players are at 20 life and hold the same number of cards, so every term cancels and the
     * total must be exactly 0. That is a weak-looking assertion that is in fact quite sharp: a life
     * or hand weight that changed SIGN, or a life lookup that read the wrong table index for one
     * player, would break the symmetry.
     * <p>
     * The per-player components are checked directly against the old life table:
     * {@code LIFE_SCORES[20] = 10000} and {@code HAND_CARD_SCORE = 5}.
     */
    @Test
    public void emptySymmetricBoardScoresZeroAndItsPartsMatchTheOldTable() {
        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        GameStateEvaluator2.PlayerEvaluateScore score =
                GameStateEvaluator2.evaluate(playerA.getId(), currentGame, false, DEFAULTS);

        Assert.assertEquals("life score at 20 life is LIFE_SCORES[20]",
                10000, score.getPlayerLifeScore());
        Assert.assertEquals("hand term is size * HAND_CARD_SCORE(5)",
                currentGame.getPlayer(playerA.getId()).getHand().size() * 5, score.getPlayerHandScore());
        Assert.assertEquals("no permanents, no permanent score", 0, score.getPlayerPermanentsScore());
        Assert.assertEquals("a symmetric position must score exactly 0", 0, score.getTotalScore());
    }

    /**
     * {@code getLifeScore} needs no game state at all, so it can be swept exhaustively rather than
     * sampled. This walks the entire reachable range against the ORIGINAL table and the original
     * above-table formula, including the negative and above-table edges.
     */
    @Test
    public void lifeScoreCurveIsUnchangedAcrossItsWholeRange() {
        final int[] oldTable = {0, 1000, 2000, 3000, 4000, 4500, 5000, 5500, 6000, 6500, 7000,
                7400, 7800, 8200, 8600, 9000, 9200, 9400, 9600, 9800, 10000};
        final int oldMaxLife = oldTable.length - 1;
        for (int life = -50; life <= 1000; life++) {
            int expected;
            if (life > oldMaxLife) {
                expected = oldTable[oldMaxLife] + (life - oldMaxLife) * 100; // LIFE_ABOVE_MULTIPLIER
            } else if (life >= 0) {
                expected = oldTable[life];
            } else {
                expected = 0;
            }
            Assert.assertEquals("life score at " + life,
                    expected, ArtificialScoringSystem.getLifeScore(life, DEFAULTS));
        }
    }

    /**
     * {@code getTappedScore} is three constants behind a type test; pin all three.
     */
    @Test
    public void tappedPenaltiesAreUnchanged() {
        addCard(Zone.BATTLEFIELD, playerA, "Balduvian Bears", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Sol Ring", 1); // artifact: the "everything else" branch

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        Assert.assertEquals("tapped creature", -100, ArtificialScoringSystem.getTappedScore(
                currentGame, getPermanent("Balduvian Bears", playerA), DEFAULTS));
        Assert.assertEquals("tapped land", -20, ArtificialScoringSystem.getTappedScore(
                currentGame, getPermanent("Forest", playerA), DEFAULTS));
        Assert.assertEquals("tapped anything else", -2, ArtificialScoringSystem.getTappedScore(
                currentGame, getPermanent("Sol Ring", playerA), DEFAULTS));
    }

    /**
     * DARRELLBEST-FORK: proves destroying your OWN creature can never score better than keeping
     * it, even though {@code overextensionPenalty} (60 in TUNED,
     * {@code GameStateEvaluator2.java} around the {@code overextensionScore} block) falls every
     * time a creature leaves an overextended board -- the exact interaction flagged as a possible
     * self-targeted-removal incentive.
     * <p>
     * <b>The audit.</b> {@code overextensionScore} is charged only past
     * {@code opponentCreatureCount + OVEREXTENSION_MARGIN(3)} ({@code ArtificialScoringSystem
     * .OVEREXTENSION_MARGIN}), and it is SUBTRACTED from the player's own score (the
     * {@code playerPermanentsScore += ... - overextensionScore - commanderRecastScore;} fold-in
     * near the end of {@code GameStateEvaluator2.evaluate}). Losing one surplus creature relieves
     * exactly {@code overextensionPenalty} (60) of that subtraction -- a swing of at most +60.
     * Against that: the creature's own {@code evaluatePermanent} score is bounded well below by
     * {@code permanentOnBattlefieldBonus} (300) alone, since {@code getFixedPermanentScore} adds
     * it unconditionally and {@code getDynamicPermanentScore} adds a non-negative amount for any
     * creature with non-negative power (clamped at 0 in the same walk that feeds
     * {@code myCreatureCount}/{@code myCreaturePower}, so a creature that COULD swing the
     * overextension term is never scored as worth less than its fixed floor). A real creature is
     * therefore worth at least ~300-600 by this evaluator, dwarfing the 60-point relief several
     * times over, before even counting the mana-source loss ({@code manaSourceValue}=60 in TUNED,
     * if the destroyed creature happened to tap for mana) or the lost
     * {@code deployedManaValueWeight} contribution -- both of which only make self-destruction
     * WORSE, never better. {@code mustAnswerScore} is untouched by an own-side change: every
     * signal it counts comes from walking the OPPONENT's battlefield, never the player's own, so
     * it cannot be the thing that flips this sign either.
     * <p>
     * Rather than trust that arithmetic in the abstract, this evaluates a real board (5 own
     * creatures against 0 opposing, so the overextension term is actually ACTIVE both before and
     * after: surplus = 5-0-3 = 2, then 4-0-3 = 1, both positive) and asserts the total score drops
     * when one of the five is removed straight off the battlefield.
     */
    @Test
    public void destroyingOwnCreatureNeverScoresBetterEvenWhenOverextended() {
        CommanderEvalParams tuned = CommanderEvalParams.TUNED;
        Assert.assertTrue("this test only exercises the interaction under scrutiny while TUNED "
                        + "actually charges an overextension penalty",
                tuned.getOverextensionPenalty() > 0);

        addCard(Zone.BATTLEFIELD, playerA, "Balduvian Bears", 5);

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        int before = GameStateEvaluator2.evaluate(playerA.getId(), currentGame, false, tuned).getTotalScore();

        List<Permanent> ownCreatures = new ArrayList<>();
        for (Permanent p : currentGame.getBattlefield().getAllActivePermanents(playerA.getId())) {
            if (p.isCreature(currentGame)) {
                ownCreatures.add(p);
            }
        }
        Assert.assertEquals("test setup sanity: 5 Balduvian Bears on the battlefield",
                5, ownCreatures.size());
        currentGame.getBattlefield().removePermanent(ownCreatures.get(0).getId());

        int after = GameStateEvaluator2.evaluate(playerA.getId(), currentGame, false, tuned).getTotalScore();

        Assert.assertTrue("removing an own creature must never score better than keeping it, even "
                        + "while overextensionPenalty is actively relieved by the loss (surplus "
                        + "5-0-3=2 before, 4-0-3=1 after -- both positive, so the relief this test "
                        + "guards against really is being applied): before=" + before + " after=" + after,
                after < before);
    }
}
