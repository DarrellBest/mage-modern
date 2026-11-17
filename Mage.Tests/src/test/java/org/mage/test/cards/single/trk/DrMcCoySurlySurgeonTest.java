package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DrMcCoySurlySurgeonTest extends CardTestPlayerBase {

    @Test
    public void test_CounterSavesFromDestruction() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 4);
        addCard(Zone.HAND, playerA, "Dr. McCoy, Surly Surgeon");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Swamp", 2);
        addCard(Zone.HAND, playerB, "Murder"); // {1}{B}{B}: needs 3
        addCard(Zone.BATTLEFIELD, playerB, "Swamp", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Dr. McCoy, Surly Surgeon");
        addTarget(playerA, "Grizzly Bears");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Murder", "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 0);
    }
}
