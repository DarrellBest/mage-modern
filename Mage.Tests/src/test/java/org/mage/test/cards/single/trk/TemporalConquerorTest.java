package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TemporalConquerorTest extends CardTestPlayerBase {

    // When this creature enters, mill three cards. Then return a land card from your graveyard to your hand.
    // If you can't, create a Treasure token.
    private static final String TC = "Temporal Conqueror";

    @Test
    public void test_ReturnsMilledLand() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, TC);
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears");
        addCard(Zone.LIBRARY, playerA, "Swamp");
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TC);
        setChoice(playerA, "Swamp");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Swamp", 1);
        assertPermanentCount(playerA, "Treasure Token", 0);
    }

    @Test
    public void test_NoLandMakesTreasure() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, TC);
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears", 3);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TC);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Grizzly Bears", 3);
        assertPermanentCount(playerA, "Treasure Token", 1);
    }
}
