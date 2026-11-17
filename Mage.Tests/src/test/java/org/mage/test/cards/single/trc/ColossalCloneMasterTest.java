package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ColossalCloneMasterTest extends CardTestPlayerBase {

    @Test
    public void test_CopyGetsLandBasedSize() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1); // 3 differently named lands
        addCard(Zone.HAND, playerA, "Colossal Clone Master");
        addCard(Zone.BATTLEFIELD, playerB, "Craw Wurm"); // 6/4

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Colossal Clone Master");
        setChoice(playerA, true);
        setChoice(playerA, "Craw Wurm");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Craw Wurm", 1);
        assertPowerToughness(playerA, "Craw Wurm", 3, 3);
    }
}
