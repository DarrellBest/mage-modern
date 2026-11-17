package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class RutherfordUpgradedEnsignTest extends CardTestPlayerBase {

    @Test
    public void test_SpacecraftSpellGivesCounter() {
        addCard(Zone.BATTLEFIELD, playerA, "Rutherford, Upgraded Ensign");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.HAND, playerA, "U.S.S. Stargazer, Here and There"); // {3} Spacecraft

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "U.S.S. Stargazer, Here and There");
        addTarget(playerA, "Rutherford, Upgraded Ensign");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Rutherford, Upgraded Ensign", CounterType.P1P1, 1);
    }
}
