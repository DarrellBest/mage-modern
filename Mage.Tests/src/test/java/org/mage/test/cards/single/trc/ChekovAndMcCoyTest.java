package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ChekovAndMcCoyTest extends CardTestPlayerBase {

    @Test
    public void test_DrawThatManyOncePerTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Chekov and McCoy");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 6);
        addCard(Zone.HAND, playerA, "Jadzia Dax, Living Legacy", 2); // Transfer Symbiont: two +1/+1 counters

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "<i>Transfer Symbiont</i>", "Chekov and McCoy");
        setChoice(playerA, true); // draw 2
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "<i>Transfer Symbiont</i>", "Chekov and McCoy");
        // second time this turn: no trigger

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 2);
        assertPowerToughness(playerA, "Chekov and McCoy", 3 + 4, 3 + 4);
    }
}
