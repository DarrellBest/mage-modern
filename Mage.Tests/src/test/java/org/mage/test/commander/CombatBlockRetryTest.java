package org.mage.test.commander;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.player.ai.commander.util.CombatInfo;
import mage.player.ai.commander.util.CombatUtil;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DARRELLBEST-FORK: proves the sacrifice-blocker RETRY LOOP in
 * {@link CombatUtil#blockWithGoodTrade2}.
 * <p>
 * <b>The bug.</b> The old code picked exactly one sacrifice candidate --
 * {@code getWorstCreature(diedBlockers)}, the lowest-power one -- and if that single creature failed
 * the {@code worthIt} gate the attacker went through completely UNBLOCKED, even when another
 * available blocker would have traded with it profitably. Measured over graded game logs: 22.7% of
 * all BLOCK decisions used 0 of the blockers available to them. Principles 16 and 19.
 * <p>
 * <b>Why this calls {@code blockWithGoodTrade2} directly</b> rather than driving the bot with
 * {@code aiPlayStep(DECLARE_BLOCKERS)} the way {@code BlockSimulationAITest} does. That helper wraps
 * {@code mage.player.ai.ComputerPlayer7} -- the MAD plugin's bot, which blocks through
 * {@code mage.player.ai.util.CombatUtil}, a DIFFERENT class from the commander fork's
 * {@code mage.player.ai.commander.util.CombatUtil} changed here. A test in that style would pass
 * whether or not this fix exists. The board is a real game built by the standard harness and the
 * function under test is the real one; only the entry point is direct. It also lets the test name
 * the weights, so both branches of the {@code blockTradeMode} gate are covered.
 * <p>
 * <b>It must run mid-game</b>, via {@code runCode} at declare-blockers, not after {@code execute()}.
 * {@code willItSurviveSimple} resolves its damage through {@code checkStateAndTriggered}, and on a
 * game the harness has already stopped no state-based action fires -- every creature then "survives"
 * its own death and every score difference collapses to a rounding error. Cost a debugging round:
 * the first version of this test asserted against {@code blockerDied=false} for a 1/3 blocking a
 * 5/5.
 * <p>
 * <b>The board, and why these three cards.</b>
 * <pre>
 *   attacker   Deadbridge Goliath 5/5   valuable, and dies to 6 power
 *   blocker A  Arashin Cleric     1/3   LOWEST POWER, so it is the one the old code tried, and
 *                                       only that one. Dies to the 5/5, deals 1, kills nothing.
 *   blocker C  Ball Lightning     6/1   dies to the 5/5 AND kills it: a profitable TRADE
 * </pre>
 * Both blockers die, so both land in {@code diedBlockers}, the survive-first branch finds nothing
 * and the sacrifice branch is the only one that runs. Measured on this exact board:
 * <pre>
 *   Arashin Cleric  DEFAULT diffBlock -1502 vs diffNonBlock -500    fails both gate branches
 *                   TUNED   diffBlock -1582 vs diffNonBlock -100    fails both gate branches
 *   Ball Lightning  DEFAULT diffBlock  +333 vs diffNonBlock -500    passes
 *                   TUNED   diffBlock  +373 vs diffNonBlock -100    passes
 * </pre>
 * Defender life is 40 so letting 5 damage through is genuinely cheap. At the harness default of 20
 * the life curve is steep enough that chumping with the Cleric is the CORRECT play, which is a
 * different decision and would not exercise this path at all.
 * <p>
 * {@link #cheapestCandidateAloneIsCorrectlyDeclined} is the control: with the Cleric as the only
 * candidate the answer must be "do not block". That is what makes the main result a retry rather
 * than a coincidence -- the Cleric is genuinely rejected, so a block by the Ball Lightning can only
 * have come from looking past it.
 *
 * @author Darrell Best
 */
public class CombatBlockRetryTest extends CardTestPlayerBaseWithAIHelps {

    private static final String ATTACKER = "Deadbridge Goliath";       // 5/5
    private static final String CHEAPEST_CANDIDATE = "Arashin Cleric"; // 1/3, chumps and dies
    private static final String TRADE_CANDIDATE = "Ball Lightning";    // 6/1, trades with the 5/5

    /** Filled in by the mid-game hook; read by the assertions after execute(). */
    private CombatInfo decision;
    private boolean hookRan;

    /**
     * The fix: the Cleric fails the gate, so the Ball Lightning must be tried and must block. Run
     * against TUNED, the configuration the live server and the bench actually use.
     */
    @Test
    public void retriesPastTheCheapestCandidateAndTrades() {
        runBlockDecision(CommanderEvalParams.TUNED, true);
        assertBlockedBy(TRADE_CANDIDATE, CommanderEvalParams.TUNED.getBlockTradeMode());
    }

    /**
     * The same board against DEFAULT, whose {@code worthIt} gate takes the other branch
     * ({@code diffBlocking >= 0 || ...}). The retry must not depend on which branch is active: this
     * change is about how many candidates reach the gate, not about the gate.
     */
    @Test
    public void retryIsIndependentOfBlockTradeMode() {
        runBlockDecision(CommanderEvalParams.DEFAULT, true);
        assertBlockedBy(TRADE_CANDIDATE, CommanderEvalParams.DEFAULT.getBlockTradeMode());
    }

    /**
     * Control. With only the losing candidate on the battlefield the correct answer is still to take
     * the damage, so the retry loop must not have become "block with something, anything".
     */
    @Test
    public void cheapestCandidateAloneIsCorrectlyDeclined() {
        runBlockDecision(CommanderEvalParams.TUNED, false);
        Assert.assertTrue("a chump block that loses more than the damage it stops must still be "
                        + "declined -- the retry loop widens the search, it must not lower the bar; got "
                        + describe(),
                decision.getCombat().isEmpty());
    }

    /**
     * Builds the board, runs a real game, and asks the commander fork's blocker logic what it would
     * do at declare-blockers.
     *
     * @param params             the weights to decide with
     * @param withTradeCandidate whether the profitable blocker is on the battlefield at all
     */
    private void runBlockDecision(CommanderEvalParams params, boolean withTradeCandidate) {
        addCard(Zone.BATTLEFIELD, playerA, ATTACKER, 1);
        addCard(Zone.BATTLEFIELD, playerB, CHEAPEST_CANDIDATE, 1);
        if (withTradeCandidate) {
            addCard(Zone.BATTLEFIELD, playerB, TRADE_CANDIDATE, 1);
        }
        // 40 life: at the harness default of 20 the life curve makes even a bad chump correct, which
        // would exercise a different branch entirely. See the class javadoc.
        setLife(playerB, 40);

        attack(1, playerA, ATTACKER);

        runCode("block decision", 1, PhaseStep.DECLARE_BLOCKERS, playerB, (info, player, game) -> {
            Permanent attacker = findPermanent(game, ATTACKER);
            Assert.assertNotNull("attacker must be on the battlefield", attacker);
            Assert.assertTrue("the hook must run with the attack already declared, otherwise this "
                    + "test is scoring an empty combat", attacker.isAttacking());

            List<Permanent> attackers = new ArrayList<>();
            attackers.add(attacker);
            List<Permanent> blockers = new ArrayList<>();
            blockers.add(findPermanent(game, CHEAPEST_CANDIDATE));
            if (withTradeCandidate) {
                blockers.add(findPermanent(game, TRADE_CANDIDATE));
            }
            for (Permanent blocker : blockers) {
                Assert.assertNotNull("blocker must be on the battlefield", blocker);
            }
            // note: blockWithGoodTrade2 mutates the blocker list it is handed, hence a fresh one
            decision = CombatUtil.blockWithGoodTrade2(game, attackers, blockers, params);
            hookRan = true;
        });

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        Assert.assertTrue("the declare-blockers hook never ran, so this test asserted nothing",
                hookRan);
        Assert.assertNotNull(decision);
    }

    private static Permanent findPermanent(Game game, String name) {
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents()) {
            if (name.equals(permanent.getName())) {
                return permanent;
            }
        }
        return null;
    }

    private void assertBlockedBy(String expectedBlocker, int blockTradeMode) {
        Assert.assertFalse("the attacker went through UNBLOCKED. The lowest-power candidate ("
                        + CHEAPEST_CANDIDATE + ") fails the worthIt gate, so the old single-candidate "
                        + "code gave up there; " + TRADE_CANDIDATE + " was available and trades with the "
                        + "attacker. blockTradeMode=" + blockTradeMode,
                decision.getCombat().isEmpty());
        List<String> blockerNames = new ArrayList<>();
        for (Map.Entry<Permanent, List<Permanent>> entry : decision.getCombat().entrySet()) {
            for (Permanent blocker : entry.getValue()) {
                blockerNames.add(blocker.getName());
            }
        }
        Assert.assertTrue("expected the profitable trade (" + expectedBlocker + ") to be chosen, got "
                        + blockerNames + "; blockTradeMode=" + blockTradeMode,
                blockerNames.contains(expectedBlocker));
    }

    private String describe() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Permanent, List<Permanent>> entry : decision.getCombat().entrySet()) {
            sb.append(entry.getKey().getName()).append(" blocked by ");
            for (Permanent blocker : entry.getValue()) {
                sb.append(blocker.getName()).append(' ');
            }
        }
        return sb.length() == 0 ? "(no blocks)" : sb.toString();
    }
}
