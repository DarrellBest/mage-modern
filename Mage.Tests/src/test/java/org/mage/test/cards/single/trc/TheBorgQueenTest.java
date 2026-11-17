package org.mage.test.cards.single.trc;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheBorgQueenTest extends CardTestPlayerBase {

    // Artifact creatures you control get +2/+2. Whenever The Borg Queen enters or attacks, target opponent exiles
    // cards from the top of their library until they exile a creature card. Assimilate that card. They put the rest
    // on the bottom in a random order.
    private static final String QUEEN = "The Borg Queen";

    @Test
    public void test_EntersAndAssimilatesFromLibrary() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1);
        addCard(Zone.HAND, playerA, QUEEN);
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Mountain");
        addCard(Zone.LIBRARY, playerB, "Grizzly Bears");
        addCard(Zone.LIBRARY, playerB, "Forest"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, QUEEN);
        addTarget(playerA, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertType("Grizzly Bears", CardType.ARTIFACT, true);
        assertSubtype("Grizzly Bears", SubType.BORG);
        assertPowerToughness(playerA, "Grizzly Bears", 2 + 1 + 2, 2 + 1 + 2);
        assertExileCount(playerB, 0); // the Forest went to the bottom
        assertLibraryCount(playerB, 2);
    }
}
