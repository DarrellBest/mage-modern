package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class LwaxanaTroiCharmingGuestTest extends CardTestPlayerBase {

    @Test
    public void test_TriggersAnAdditionalTimeWhileTapped() {
        addCard(Zone.BATTLEFIELD, playerA, "Lwaxana Troi, Charming Guest");
        addCard(Zone.BATTLEFIELD, playerA, "Kes, Evolving Telepath"); // end step trigger
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}, {T}: Choose another target creature", "Kes, Evolving Telepath");

        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        // Kes's trigger resolved twice: 1 counter + draw 1, then 2 counters + draw 2
        assertCounterCount(playerA, "Kes, Evolving Telepath", CounterType.TIME, 2);
        assertHandCount(playerA, 1 + 2);
    }
}
