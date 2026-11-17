package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ExploratoryMissionTest extends CardTestPlayerBase {

    @Test
    public void test_LandfallExilesAndCanPlay() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Exploratory Mission");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Mountain");
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt");
        addCard(Zone.LIBRARY, playerA, "Forest"); // top: exiled, then Bolt

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Mountain");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Exploratory Mission", CounterType.QUEST, 1);
        assertExileCount(playerA, "Forest", 1);
        assertLife(playerB, 20 - 3);
    }
}
