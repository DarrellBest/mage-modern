package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class USSFarragutBoldExplorerTest extends CardTestPlayerBase {

    // Landfall -- you may put a creature card from your hand onto the battlefield. That creature gains haste. At the
    // beginning of the next end step, put it on the bottom of your library. When you do, draw a card.
    @Test
    public void test_LandfallCheatThenBottomAndDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "U.S.S. Farragut, Bold Explorer");
        addCard(Zone.HAND, playerA, "Forest");
        addCard(Zone.HAND, playerA, "Hill Giant");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Forest");
        setChoice(playerA, true);
        setChoice(playerA, "Hill Giant");
        attack(1, playerA, "Hill Giant"); // it has haste

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 3);
        assertPermanentCount(playerA, "Hill Giant", 0);
        assertHandCount(playerA, 1); // drew a card
    }
}
