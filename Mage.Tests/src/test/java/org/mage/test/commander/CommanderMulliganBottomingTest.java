package org.mage.test.commander;

import mage.constants.RangeOfInfluence;
import mage.player.ai.commander.ComputerPlayer6;
import mage.player.ai.commander.score.CommanderEvalParams;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: PROOF that the London mulligan's bottoming question actually reaches the
 * fork's land-protection logic.
 * <p>
 * The bug this guards against already happened, silently, and survived a full night of tuning.
 * The bottoming logic was written only on {@code choose(Outcome, Target, Ability, Game, Map)},
 * while {@code LondonMulligan.mulligan} asks the question through
 * {@code player.chooseTarget(Outcome.Discard, target, null, game)} -- a different method. So the
 * override never ran. Every mulligan still bottomed cards through the generic AI target logic,
 * which is the code path that discards lands, and the reported symptom it was written to fix (a
 * kept hand with no lands, then six turns of Pass) was never addressed.
 * <p>
 * Nothing caught it: the bench asserts nothing about mulligans, and a parameter sweep cannot
 * distinguish a setting that does nothing from a setting that does not help, so {@code
 * smartMulligan} sat in TUNED looking like a measured win.
 * <p>
 * A behavioural test would be better, but it needs a real game with a forced mulligan. This
 * asserts the wiring instead, which is precisely what broke: the override must exist on the
 * method the engine actually calls. If someone later moves the logic back onto {@code choose}
 * alone, or renames it, this fails.
 */
public class CommanderMulliganBottomingTest {

    /** The exact call LondonMulligan makes: chooseTarget(Outcome, Target, Ability, Game). */
    @Test
    public void forkOverridesTheMethodTheMulliganActuallyCalls() throws Exception {
        Method m = ComputerPlayer6.class.getDeclaredMethod("chooseTarget",
                mage.constants.Outcome.class,
                mage.target.Target.class,
                mage.abilities.Ability.class,
                mage.game.Game.class);
        assertNotNull("ComputerPlayer6 must override chooseTarget(Outcome, Target, Ability, Game) -- "
                + "LondonMulligan asks the bottoming question through it, so bottoming logic placed "
                + "anywhere else is dead code", m);
        assertTrue("the override must be declared on the fork's own class, not merely inherited",
                m.getDeclaringClass() == ComputerPlayer6.class);
    }

    /** The bottoming helper both entry points share must exist and be reachable. */
    @Test
    public void bottomingHelperExists() throws Exception {
        Method m = ComputerPlayer6.class.getDeclaredMethod("bottomWorstCards",
                mage.target.Target.class,
                mage.abilities.Ability.class,
                mage.game.Game.class);
        assertNotNull(m);
    }

    /** smartMulligan must still be on in TUNED, or both overrides no-op. */
    @Test
    public void tunedStillEnablesSmartMulligan() {
        assertTrue("TUNED must keep smartMulligan enabled, otherwise the bottoming logic is skipped",
                CommanderEvalParams.TUNED.getSmartMulligan() >= 1);
    }

    /** A player builds with TUNED weights and exposes the override. */
    @Test
    public void playerConstructsWithTunedWeights() {
        ComputerPlayer6 player = new ComputerPlayer6("bottoming-test", RangeOfInfluence.ONE, 6,
                CommanderEvalParams.TUNED);
        assertNotNull(player);
        assertTrue(player.getName().equals("bottoming-test"));
    }
}
