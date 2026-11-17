package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class LtCmdrGeordiLaForgeTest extends CardTestPlayerBase {

    @Test
    public void test_DoublesStationCounters() {
        addCard(Zone.BATTLEFIELD, playerA, "Lt. Cmdr. Geordi La Forge");
        addCard(Zone.BATTLEFIELD, playerA, "Transwarp Hub");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant"); // power 3

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Transwarp Hub", CounterType.CHARGE, 6);
    }
}
