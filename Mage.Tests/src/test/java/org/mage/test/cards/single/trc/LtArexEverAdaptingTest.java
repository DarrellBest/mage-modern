package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class LtArexEverAdaptingTest extends CardTestPlayerBase {

    @Test
    public void test_DrawThreePutLandsAndSize() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "Lt. Arex, Ever-Adapting");
        addCard(Zone.LIBRARY, playerA, "Swamp");
        addCard(Zone.LIBRARY, playerA, "Mountain");
        addCard(Zone.LIBRARY, playerA, "Plains"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lt. Arex, Ever-Adapting");
        setChoice(playerA, "Plains^Mountain^Swamp");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Plains", 1);
        assertTapped("Plains", true);
        // Forest, Island, Plains, Mountain, Swamp
        assertPowerToughness(playerA, "Lt. Arex, Ever-Adapting", 5, 5);
    }
}
