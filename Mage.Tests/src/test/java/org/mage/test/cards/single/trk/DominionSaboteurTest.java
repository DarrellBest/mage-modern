package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DominionSaboteurTest extends CardTestPlayerBase {

    @Test
    public void test_CopiesWithCountersNotLegendary() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.HAND, playerA, "Dominion Saboteur");
        addCard(Zone.BATTLEFIELD, playerA, "Ensign Harry Kim, Operations"); // legendary
        addCard(Zone.BATTLEFIELD, playerA, "Isolinear Processor");

        // give Kim a counter first: Dominion Saboteur (3 power) enters later and copies Kim with it
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Dominion Saboteur");
        setChoice(playerA, true);
        setChoice(playerA, "Ensign Harry Kim, Operations");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // two Kims: the copy isn't legendary, so no legend rule
        assertPermanentCount(playerA, "Ensign Harry Kim, Operations", 2);
    }
}
