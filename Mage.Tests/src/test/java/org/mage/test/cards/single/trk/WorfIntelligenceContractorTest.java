package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class WorfIntelligenceContractorTest extends CardTestPlayerBase {

    @Test
    public void test_BorrowsSmallCreature() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.HAND, playerA, "Worf, Intelligence Contractor");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears", 1, true); // tapped

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Worf, Intelligence Contractor");
        addTarget(playerA, "Grizzly Bears");
        checkSubType("warrior", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Grizzly Bears", SubType.WARRIOR, true);
        attack(1, playerA, "Grizzly Bears"); // untapped, has haste

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 2);
        assertPermanentCount(playerB, "Grizzly Bears", 1);
    }
}
