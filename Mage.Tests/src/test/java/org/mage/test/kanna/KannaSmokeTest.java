package org.mage.test.kanna;

import mage.abilities.Ability;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.target.Target;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: guards the pivot away from search. If Kanna ever regains an
 * MCTS/minimax ancestor these fail, which is the point.
 */
public class KannaSmokeTest {

    private ComputerPlayerKanna kanna() {
        return new ComputerPlayerKanna("Kanna", RangeOfInfluence.ONE, 6);
    }

    @Test
    public void extendsBaseComputerPlayer() {
        assertTrue(kanna() instanceof ComputerPlayer);
    }

    @Test
    public void hasNoSearchBasedAncestor() {
        Class<?> type = kanna().getClass();
        while (type != null) {
            String name = type.getName();
            assertFalse("Kanna must not inherit from a search player: " + name,
                    name.contains("ComputerPlayerMCTS") || name.contains("ComputerPlayer6")
                            || name.contains("ComputerPlayer7"));
            type = type.getSuperclass();
        }
    }

    @Test
    public void overridesPriorityRatherThanInheritingTheNoOp() throws Exception {
        // ComputerPlayer.priority() is "minimum implementation for do nothing" -- it just
        // passes. Inheriting it means passing every window forever, silently.
        assertEquals(ComputerPlayerKanna.class,
                kanna().getClass().getMethod("priority", mage.game.Game.class).getDeclaringClass());
    }

    @Test
    public void overridesCombatAndTargetingRatherThanInheritingTheNoOps() throws Exception {
        // ComputerPlayer.selectAttackers()/selectBlockers() are empty no-op stubs ("do
        // nothing, parent class must implement it") -- ComputerPlayer6 implements real
        // combat, ComputerPlayer does not, and Kanna extends ComputerPlayer, not
        // ComputerPlayer6. Inheriting them means declaring zero attackers/blockers
        // forever, silently -- the same trap as an inherited priority(), just in combat.
        assertEquals(ComputerPlayerKanna.class,
                kanna().getClass().getMethod("selectAttackers", Game.class, UUID.class).getDeclaringClass());
        assertEquals(ComputerPlayerKanna.class,
                kanna().getClass().getMethod("selectBlockers", Ability.class, Game.class, UUID.class)
                        .getDeclaringClass());
        assertEquals(ComputerPlayerKanna.class,
                kanna().getClass().getMethod("chooseTarget", Outcome.class, Target.class, Ability.class, Game.class)
                        .getDeclaringClass());
    }

    @Test
    public void copyPreservesConfiguration() {
        ComputerPlayerKanna original = kanna();
        original.setModel("some-model:latest");
        ComputerPlayerKanna copy = original.copy();
        assertEquals("some-model:latest", copy.getModel());
    }
}
