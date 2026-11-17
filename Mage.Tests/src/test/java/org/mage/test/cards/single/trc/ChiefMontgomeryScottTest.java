package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ChiefMontgomeryScottTest extends CardTestPlayerBase {

    @Test
    public void test_PutsCreatureInWithCounters() {
        addCard(Zone.BATTLEFIELD, playerA, "Chief Montgomery Scott");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2); // 2 differently named lands
        addCard(Zone.HAND, playerA, "Hill Giant");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "<i>Strange new worlds</i>");
        setChoice(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Hill Giant", CounterType.P1P1, 2);
    }
}
