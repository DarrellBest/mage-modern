package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TuvokChiefTacticalOfficerTest extends CardTestPlayerBase {

    @Test
    public void test_FlashedInOutsideCombatNoTrigger() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Tuvok, Chief Tactical Officer");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Tuvok, Chief Tactical Officer");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Tuvok, Chief Tactical Officer", 1);
    }
}
