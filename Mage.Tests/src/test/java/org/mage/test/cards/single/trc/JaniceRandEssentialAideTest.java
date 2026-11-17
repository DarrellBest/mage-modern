package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestCommanderDuelBase;

public class JaniceRandEssentialAideTest extends CardTestCommanderDuelBase {

    @Test
    public void test_CommanderEntersGetsCounterAndLand() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Janice Rand, Essential Aide");
        addCard(Zone.COMMAND, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Swamp");
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt"); // top: revealed, goes to the bottom

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 1);
        assertPermanentCount(playerA, "Swamp", 1);
        assertTapped("Swamp", true);
        assertLibraryCount(playerA, "Lightning Bolt", 1);
    }
}
