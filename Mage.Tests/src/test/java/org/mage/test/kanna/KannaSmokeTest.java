package org.mage.test.kanna;

import mage.constants.RangeOfInfluence;
import mage.player.ai.ComputerPlayer;
import mage.player.ai.kanna.ComputerPlayerKanna;
import org.junit.Test;

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

    // NOTE: a getDeclaringClass()-shaped test for selectAttackers/selectBlockers used to
    // live here (guarding that Kanna declares its own overrides rather than inheriting
    // ComputerPlayer's empty no-op stubs). Removed: Kanna already declared both overrides
    // before Critical 1 was found, so that assertion would have passed against the
    // defective code too -- it guarded "the override exists", not "the override doesn't
    // silently degrade to a no-op on the failure path", which was the actual defect.
    // Replaced by KannaFallbackAITest (org.mage.test.AI.basic), a behavioural test that
    // forces every model call to fail and asserts a real attack still gets declared.

    @Test
    public void copyPreservesConfiguration() {
        ComputerPlayerKanna original = kanna();
        original.setModel("some-model:latest");
        ComputerPlayerKanna copy = original.copy();
        assertEquals("some-model:latest", copy.getModel());
    }
}
