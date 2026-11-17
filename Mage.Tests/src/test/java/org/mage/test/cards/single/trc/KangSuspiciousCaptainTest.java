package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class KangSuspiciousCaptainTest extends CardTestPlayerBase {

    @Test
    public void test_GoadsBothAndCountersOnAttack() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 4);
        addCard(Zone.HAND, playerA, "Kang, Suspicious Captain");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Kang, Suspicious Captain");
        addTarget(playerA, "Grizzly Bears");
        // turn 2: the goaded Bears must attack A
        addTarget(playerA, "Kang, Suspicious Captain"); // counter on Kang when goaded creatures attack

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 - 2);
        assertCounterCount(playerA, "Kang, Suspicious Captain", CounterType.P1P1, 1);
    }
}
