package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class PavelChekovFirstPostingTest extends CardTestPlayerBase {

    @Test
    public void test_DrawStepDigsForNonland() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.BATTLEFIELD, playerA, "Pavel Chekov, First Posting");
        addCard(Zone.LIBRARY, playerA, "Hill Giant");
        addCard(Zone.LIBRARY, playerA, "Forest", 2); // top

        setChoice(playerA, true);  // replace the draw
        setChoice(playerA, false); // nonland

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.PRECOMBAT_MAIN);
        execute();

        assertHandCount(playerA, "Hill Giant", 1);
        assertLibraryCount(playerA, "Forest", 2);
    }
}
