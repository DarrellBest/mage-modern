package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class LaForgeThoughtfulAsEverTest extends CardTestPlayerBase {

    @Test
    public void test_FaeriePerPermanentYouPutACounterOn() {
        addCard(Zone.BATTLEFIELD, playerA, "La Forge, Thoughtful as Ever");
        addCard(Zone.BATTLEFIELD, playerA, "Transwarp Hub");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station"); // charge counters on the Hub
        setChoice(playerA, "Hill Giant");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "{T}: For each permanent");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Faerie Token", 1);
    }
}
