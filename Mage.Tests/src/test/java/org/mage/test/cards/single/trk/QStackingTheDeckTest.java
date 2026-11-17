package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class QStackingTheDeckTest extends CardTestPlayerBase {

    @Test
    public void test_ExiledCardCanBeCopiedAndCast() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Forest", 3);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt"); // the only nonland: always the one exiled
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        addCard(Zone.HAND, playerA, "Q, Stacking the Deck");
        addCard(Zone.HAND, playerA, "Grizzly Bears"); // exiled as the cost

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Q, Stacking the Deck");
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Exile a card from your hand");
        setChoice(playerA, "Grizzly Bears"); // the cost: exile it from hand
        setChoice(playerA, true);    // cast the copy (paying {R})
        addTarget(playerA, playerB);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertExileCount(playerA, "Lightning Bolt", 1); // the original stays exiled
        assertExileCount(playerA, "Grizzly Bears", 1);
    }
}
