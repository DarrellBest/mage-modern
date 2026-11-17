package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class AlexanderShipsFoolTest extends CardTestPlayerBase {

    @Test
    public void test_DamageEqualsGreatestManaValueRevealed() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Alexander, Ship's Fool");
        addCard(Zone.LIBRARY, playerA, "Hill Giant");   // mana value 4
        addCard(Zone.LIBRARY, playerB, "Grizzly Bears"); // mana value 2

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Each player reveals");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 4); // the only opponent
        assertHandCount(playerA, "Hill Giant", 1);
        assertHandCount(playerB, "Grizzly Bears", 1);
    }
}
