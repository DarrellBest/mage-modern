package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class USSEnterpriseCTimeTossedTest extends CardTestPlayerBase {

    // When U.S.S. Enterprise-C enters or dies, you may discard your hand. If you do, draw a card for each card discarded this way.
    @Test
    public void test_EntersWheelsHand() {
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 5);
        addCard(Zone.HAND, playerA, "U.S.S. Enterprise-C, Time-Tossed");
        addCard(Zone.HAND, playerA, "Grizzly Bears", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "U.S.S. Enterprise-C, Time-Tossed");
        setChoice(playerA, true);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Grizzly Bears", 2);
        assertHandCount(playerA, 2);
    }
}
