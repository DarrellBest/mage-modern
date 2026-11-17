package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class AmbassadorKEhleyrTest extends CardTestPlayerBase {

    @Test
    public void test_ParleyCountsNonlands() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Ambassador K'Ehleyr");
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears"); // nonland on top
        addCard(Zone.LIBRARY, playerB, "Lightning Bolt"); // nonland on top

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.DECLARE_ATTACKERS);
        execute();

        assertCounterCount(playerA, "Ambassador K'Ehleyr", CounterType.P1P1, 2);
        assertHandCount(playerA, "Grizzly Bears", 1);
        assertHandCount(playerB, "Lightning Bolt", 1);
    }
}
