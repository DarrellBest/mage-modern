package org.mage.test.commander;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.commander.ComputerPlayerCommander;
import mage.player.ai.commander.score.CommanderEvalParams;
import mage.players.PlayerImpl;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * DARRELLBEST-FORK: guards the one HARD invariant of the adaptive attack posture
 * ({@code ComputerPlayer6.effectiveAttackAggression}, principles 4 and 21):
 * <p>
 * <b>at {@code attackAggression == 0} the board must not get a vote.</b> Mode 0 is upstream's
 * "attack only with creatures no blocker could kill", and DEFAULT sits on it, so a board-state
 * escalation that leaked into mode 0 would silently change historical behaviour, the
 * {@code CommanderEvalParamsScoreTest} baseline's sibling behaviour, and the G0 control -- while
 * looking like a pure addition in review.
 * <p>
 * The guard is an early return placed ABOVE the board scan, so this test passes {@code null} for
 * both the game and the opponent list: if a future refactor moves the guard below the scan, or
 * drops it, the method reaches those arguments and this test fails with an NPE rather than quietly
 * escalating. That is the point -- the assertion is as much "it never looked at the board" as it is
 * "it returned 0".
 * <p>
 * The escalation path itself (life comfortable AND own power >= incoming power) IS now asserted
 * on a real board -- see {@link #emptyOpposingBoardDoesNotEscalate} below -- for the one case that
 * turned out to matter most: an opponent with NOTHING on the battlefield. The general
 * lands-comfortable/board-is-ours escalation with a genuine opposing threat still isn't
 * independently measured here (it needs a scripted combat, not just a static board), and remains
 * exercised only by the FFA bench.
 *
 * @author Darrell Best
 */
public class AttackPostureTest extends CardTestPlayerBaseWithAIHelps {

    private static Method effectiveAttackAggression() throws Exception {
        Method m = Class.forName("mage.player.ai.commander.ComputerPlayer6")
                .getDeclaredMethod("effectiveAttackAggression", mage.game.Game.class, List.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * Points an externally-built commander bot's {@code playerId} (inherited from
     * {@link PlayerImpl}, declared {@code final}) at a REAL player already seated in a CardTest
     * game, so {@code effectiveAttackAggression}'s board/life reads resolve against that player's
     * actual battlefield and life total instead of a random id nothing in the game recognizes.
     * {@code setAccessible} on a final instance field still permits {@code Field#set} at runtime
     * (only static final fields and the JDK's own strongly-encapsulated internals are blocked), so
     * this works even though the field is declared final.
     */
    private static void bindPlayerId(ComputerPlayerCommander bot, UUID id) throws Exception {
        Field f = PlayerImpl.class.getDeclaredField("playerId");
        f.setAccessible(true);
        f.set(bot, id);
    }

    /** Mode 0 stays mode 0, and gets there without reading the board at all. */
    @Test
    public void historicalModeNeverEscalates() throws Exception {
        ComputerPlayerCommander bot = new ComputerPlayerCommander(
                "hist", RangeOfInfluence.ALL, 6, CommanderEvalParams.DEFAULT);
        Assert.assertEquals("this test is only meaningful while DEFAULT is on aggression 0",
                0, CommanderEvalParams.DEFAULT.getAttackAggression());

        // null game + null opponents: reaching either means the guard moved below the board scan
        Object result = effectiveAttackAggression().invoke(bot, (mage.game.Game) null, (List<UUID>) null);
        Assert.assertEquals("attackAggression 0 is upstream's historical mode -- no board-state "
                + "escalation may apply to it", 0, result);
    }

    /** TUNED must stay opted IN, otherwise the whole adaptive path is dead code on the live bot. */
    @Test
    public void tunedOptsIntoTheBoardStateGate() {
        Assert.assertTrue("TUNED must keep attackAggression >= 1, or effectiveAttackAggression "
                        + "returns early on the configuration the server actually runs",
                CommanderEvalParams.TUNED.getAttackAggression() >= 1);
    }

    /**
     * DARRELLBEST-FORK: the regression this whole class exists to catch -- confirmed live-log root
     * cause of 12 suicide attacks in the sampled games (e.g. a 1/1 walking into an untapped 4/4).
     * <p>
     * Before the fix, {@code incomingPower == 0} (an opponent with no creatures at all) passed
     * BOTH gates trivially: {@code life > incomingPower * 2} is {@code life > 0}, true at any
     * positive life, and {@code ownPower >= incomingPower} is {@code ownPower >= 0}, true
     * whatever the board looks like. So a bot facing an empty opposing board "stabilized" by this
     * arithmetic before either side had played a single creature, escalating to level 3 --
     * dropping the attack trade bar from "the blocker is worth double" to "the blocker is worth
     * more at all" -- against a board with nothing to have stabilized against.
     * <p>
     * Deliberately sets life LOW (5, not comfortable by any real standard) so this proves the
     * empty-board guard specifically and not a coincidental life-based no-escalation: under the
     * pre-fix formula, life=5 with incomingPower=0 still satisfies {@code 5 > 0} and would have
     * escalated regardless.
     */
    @Test
    public void emptyOpposingBoardDoesNotEscalate() throws Exception {
        // ownPower > 0 so "the board is ours" would also trivially hold under the old formula --
        // isolating the incoming-power guard as the only thing standing between this test and
        // escalation.
        addCard(Zone.BATTLEFIELD, playerA, "Balduvian Bears", 1); // a 2/2, ownPower = 2

        setStopAt(1, PhaseStep.END_TURN);
        setStrictChooseMode(true);
        execute();

        setLife(playerA, 5);

        CommanderEvalParams tuned = CommanderEvalParams.TUNED;
        Assert.assertTrue("this test only exercises the gate while TUNED actually opts into it",
                tuned.getAttackAggression() >= 1);

        ComputerPlayerCommander bot = new ComputerPlayerCommander("poser", RangeOfInfluence.ALL, 6, tuned);
        bindPlayerId(bot, playerA.getId());

        List<UUID> opponents = Collections.singletonList(playerB.getId());
        Object result = effectiveAttackAggression().invoke(bot, currentGame, opponents);
        Assert.assertEquals("playerB's battlefield is empty (incoming power 0) -- that must never "
                        + "escalate aggression, however comfortable life or own board strength look",
                tuned.getAttackAggression(), result);
    }
}
