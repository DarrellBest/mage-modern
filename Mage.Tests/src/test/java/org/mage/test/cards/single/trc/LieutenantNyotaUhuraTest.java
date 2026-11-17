package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class LieutenantNyotaUhuraTest extends CardTestPlayerBase {

    @Test
    public void test_CastsOpponentsSorceryForFree() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Lieutenant Nyota Uhura");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2); // 2 differently named lands
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Forest");
        addCard(Zone.LIBRARY, playerB, "Lightning Bolt"); // top

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "<i>Strange new worlds</i>", playerB);
        setChoice(playerA, true); // cast it
        addTarget(playerA, playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertGraveyardCount(playerB, "Lightning Bolt", 1);
        assertLibraryCount(playerB, "Forest", 1);
    }
}
