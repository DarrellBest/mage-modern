package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class BranchingTimelineTest extends CardTestPlayerBase {

    @Test
    public void test_PutsRevealedPermanentsIntoPlay() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 6);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1); // 3 differently named lands
        addCard(Zone.HAND, playerA, "Branching Timeline");
        addCard(Zone.LIBRARY, playerA, "Hill Giant"); // 4th: not revealed
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt");
        addCard(Zone.LIBRARY, playerA, "Ornithopter");
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Branching Timeline");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertTapped("Grizzly Bears", true);
        assertPermanentCount(playerA, "Ornithopter", 1);
        assertLibraryCount(playerA, "Lightning Bolt", 1);
        assertLibraryCount(playerA, "Hill Giant", 1);
    }
}
