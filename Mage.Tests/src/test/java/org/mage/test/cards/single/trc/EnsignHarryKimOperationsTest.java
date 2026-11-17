package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class EnsignHarryKimOperationsTest extends CardTestPlayerBase {

    private static final String KIM = "Ensign Harry Kim, Operations"; // 1/2

    @Test
    public void test_GrowsOnlyForBiggerCreatures() {
        addCard(Zone.BATTLEFIELD, playerA, KIM);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, "Grizzly Bears"); // power 2 > 1: counter
        addCard(Zone.HAND, playerA, "Llanowar Elves"); // power 1, not greater

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Llanowar Elves");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, KIM, CounterType.P1P1, 1);
    }
}
