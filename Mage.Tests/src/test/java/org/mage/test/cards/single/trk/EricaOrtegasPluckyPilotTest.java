package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class EricaOrtegasPluckyPilotTest extends CardTestPlayerBase {

    @Test
    public void test_TappedPutsLandThenReturnsOne() {
        addCard(Zone.BATTLEFIELD, playerA, "Erica Ortegas, Plucky Pilot");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Forest");

        attack(1, playerA, "Erica Ortegas, Plucky Pilot");
        setChoice(playerA, true);
        setChoice(playerA, "Forest");     // put onto the battlefield
        addTarget(playerA, "Mountain");   // end step: return a land

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Forest", 1);
        assertHandCount(playerA, "Mountain", 1);
    }
}
