package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class InfiniteDiversityTest extends CardTestPlayerBase {

    @Test
    public void test_CopiesPerCreatureType() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 9);
        addCard(Zone.HAND, playerA, "Infinite Diversity");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // Bear
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");    // Giant
        addCard(Zone.GRAVEYARD, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Infinite Diversity", "Lightning Bolt");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3 - 3); // two copies, cast for free
        assertExileCount(playerA, "Infinite Diversity", 1);
        assertExileCount(playerA, "Lightning Bolt", 1);
    }
}
