package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class RightOfVengeanceTest extends CardTestPlayerBase {

    // Until end of turn, whenever a creature deals combat damage to you, destroy that creature
    // and create a 2/1 red Klingon Warrior creature token with haste.
    @Test
    public void test_DestroysAttackerThatHitYou() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 4);
        addCard(Zone.HAND, playerA, "Right of Vengeance");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant"); // doesn't attack

        attack(2, playerB, "Grizzly Bears");
        castSpell(2, PhaseStep.DECLARE_BLOCKERS, playerA, "Right of Vengeance");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 - 2);
        assertGraveyardCount(playerB, "Grizzly Bears", 1);
        assertPermanentCount(playerB, "Hill Giant", 1);
        assertPermanentCount(playerA, "Klingon Warrior Token", 1);
    }
}
