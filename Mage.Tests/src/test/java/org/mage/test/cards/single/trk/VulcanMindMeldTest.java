package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class VulcanMindMeldTest extends CardTestPlayerBase {

    @Test
    public void test_CastOpponentsCardWithAnyMana() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "Vulcan Mind-Meld");
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Forest");
        addCard(Zone.LIBRARY, playerB, "Lightning Bolt"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Vulcan Mind-Meld", playerB);
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Lightning Bolt", playerB); // {R} paid with an Island

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertExileCount(playerA, 1); // B exiled A's top card face down
    }
}
