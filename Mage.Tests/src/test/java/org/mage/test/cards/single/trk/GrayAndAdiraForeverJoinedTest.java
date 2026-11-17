package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GrayAndAdiraForeverJoinedTest extends CardTestPlayerBase {

    @Test
    public void test_AfterNoncreatureSpellCopiesAtCombat() {
        addCard(Zone.BATTLEFIELD, playerA, "Gray and Adira, Forever Joined");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        addTarget(playerA, "Hill Giant"); // beginning of combat trigger
        attack(1, playerA, "Hill Giant", playerB);

        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1); // the token was sacrificed
    }
}
