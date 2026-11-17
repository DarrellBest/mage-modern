package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class PeaceInOurTimeTest extends CardTestPlayerBase {

    // At the beginning of combat on each player's turn, that player may draw a card. If they do, they can't attack
    // this turn and you put a peace counter on this enchantment.

    @Test
    public void test_DrawingMeansNoAttacking() {
        addCard(Zone.BATTLEFIELD, playerA, "Peace in Our Time");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        setChoice(playerA, false); // turn 1: A declines
        setChoice(playerB, true);  // turn 2: B draws...
        attack(2, playerB, "Grizzly Bears"); // ...so this attack must be refused

        setStopAt(2, PhaseStep.END_TURN);
        try {
            execute();
            Assert.fail("B drew from Peace in Our Time, so B must not be able to attack");
        } catch (AssertionError e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("PlayerB must have 0 actions but found 1"));
        }

        assertLife(playerA, 20);
        assertCounterCount(playerA, "Peace in Our Time", CounterType.PEACE, 1);
    }

    @Test
    public void test_ControlDecliningKeepsAttacking() {
        addCard(Zone.BATTLEFIELD, playerA, "Peace in Our Time");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        setChoice(playerA, false);
        setChoice(playerB, false); // B declines
        attack(2, playerB, "Grizzly Bears");

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 - 2);
        assertCounterCount(playerA, "Peace in Our Time", CounterType.PEACE, 0);
    }
}
