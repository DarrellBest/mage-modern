package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class BrazenBluffTest extends CardTestPlayerBase {

    @Test
    public void test_OpponentKeepsSpellSoYouCastFree() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.HAND, playerA, "Brazen Bluff");
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.BATTLEFIELD, playerB, "Island", 3);
        addCard(Zone.HAND, playerB, "Divination");

        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Divination");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerA, "Brazen Bluff", "Divination", "Divination");
        setChoice(playerB, false);  // don't exile Divination
        setChoice(playerA, true);   // cast a free spell
        addTarget(playerA, playerB); // Lightning Bolt

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertHandCount(playerB, 1 + 2); // draw step + Divination
    }
}
