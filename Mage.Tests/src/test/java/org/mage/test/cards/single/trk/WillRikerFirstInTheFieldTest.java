package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class WillRikerFirstInTheFieldTest extends CardTestPlayerBase {

    @Test
    public void test_TwoLandsInThenSacrificed() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.BATTLEFIELD, playerA, "Will Riker, First in the Field");
        addCard(Zone.LIBRARY, playerA, "Island");
        addCard(Zone.LIBRARY, playerA, "Swamp");
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears");
        addCard(Zone.LIBRARY, playerA, "Forest"); // top

        checkPermanentCount("forest in", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Forest", 1);
        checkPermanentCount("swamp in", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Swamp", 1);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Forest", 0);
        assertPermanentCount(playerA, "Swamp", 0);
        assertGraveyardCount(playerA, "Forest", 1);
        assertLibraryCount(playerA, "Grizzly Bears", 1);
        assertLibraryCount(playerA, "Island", 1);
    }
}
