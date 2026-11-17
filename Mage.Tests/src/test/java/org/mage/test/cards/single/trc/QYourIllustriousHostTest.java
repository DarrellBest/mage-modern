package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class QYourIllustriousHostTest extends CardTestPlayerBase {

    @Test
    public void test_TapThreeFaeriesCastFree() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Q, Your Illustrious Host");
        addCard(Zone.BATTLEFIELD, playerA, "Spellstutter Sprite", 3);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt");
        addCard(Zone.LIBRARY, playerA, "Forest"); // top: exiled first

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Tap three untapped Faeries");
        setChoice(playerA, "Spellstutter Sprite^Spellstutter Sprite^Spellstutter Sprite");
        setChoice(playerA, true); // cast Bolt free
        addTarget(playerA, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertExileCount(playerA, "Forest", 1);
    }
}
