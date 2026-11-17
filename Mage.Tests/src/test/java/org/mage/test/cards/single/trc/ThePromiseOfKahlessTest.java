package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ThePromiseOfKahlessTest extends CardTestPlayerBase {

    @Test
    public void test_SacrificeThenMassReturn() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 5);
        addCard(Zone.HAND, playerA, "The Promise of Kahless");
        addCard(Zone.BATTLEFIELD, playerA, "Targ Wrangler");  // Klingon Warrior: sacrificed
        addCard(Zone.GRAVEYARD, playerA, "Qo'noS Quartermaster"); // Klingon Warrior
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "The Promise of Kahless");
        setChoice(playerA, true);
        setChoice(playerA, "Targ Wrangler");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Targ Wrangler", 1); // "all other": not the sacrificed one
        assertPermanentCount(playerA, "Qo'noS Quartermaster", 1);
        assertGraveyardCount(playerA, "Grizzly Bears", 1);
    }
}
