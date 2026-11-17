package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GiftOfPerfectionTest extends CardTestPlayerBase {

    @Test
    public void test_AssimilatesFirstCreatureRevealed() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.HAND, playerA, "Gift of Perfection");
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Hill Giant");
        addCard(Zone.LIBRARY, playerB, "Island"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Gift of Perfection");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertSubtype("Hill Giant", SubType.BORG);
        assertPowerToughness(playerA, "Hill Giant", 4, 4);
        assertLibraryCount(playerB, "Island", 1);
    }
}
