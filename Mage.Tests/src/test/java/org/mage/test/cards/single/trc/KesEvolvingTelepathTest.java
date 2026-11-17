package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class KesEvolvingTelepathTest extends CardTestPlayerBase {

    // At the beginning of your end step, put a time counter on Kes, then draw X cards, where X is the number of
    // time counters on her. Then if she has nine or more time counters on her, sacrifice her.
    private static final String KES = "Kes, Evolving Telepath";

    @Test
    public void test_CountersGrowAndDrawMatches() {
        addCard(Zone.BATTLEFIELD, playerA, KES);

        setStrictChooseMode(true);
        setStopAt(4, PhaseStep.UPKEEP);
        execute();

        // turn 1 end: 1 counter, draw 1; turn 3: draw step 1, end: 2 counters, draw 2
        assertCounterCount(playerA, KES, CounterType.TIME, 2);
        assertHandCount(playerA, 1 + 1 + 2);
    }
}
