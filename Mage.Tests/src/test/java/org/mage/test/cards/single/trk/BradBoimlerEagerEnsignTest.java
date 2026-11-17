package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class BradBoimlerEagerEnsignTest extends CardTestPlayerBase {

    @Test
    public void test_AfterTappingCountersPlusOne() {
        addCard(Zone.BATTLEFIELD, playerA, "Brad Boimler, Eager Ensign");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, "Jadzia Dax, Living Legacy"); // Transfer Symbiont: two +1/+1 counters

        attack(1, playerA, "Brad Boimler, Eager Ensign");
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "<i>Transfer Symbiont</i>", "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 3);
        assertLife(playerA, 22); // lifelink
    }
}
