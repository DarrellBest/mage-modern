package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SuluHeroicHelmsmanTest extends CardTestPlayerBase {

    @Test
    public void test_StationsTripled() {
        addCard(Zone.BATTLEFIELD, playerA, "Sulu, Heroic Helmsman"); // power 2
        addCard(Zone.BATTLEFIELD, playerA, "Transwarp Hub");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Sulu, Heroic Helmsman");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Transwarp Hub", CounterType.CHARGE, 6);
    }

    @Test
    public void test_CrewsTripled() {
        addCard(Zone.BATTLEFIELD, playerA, "Sulu, Heroic Helmsman"); // power 2, crews as 6
        addCard(Zone.BATTLEFIELD, playerA, "Skysovereign, Consul Flagship"); // crew 3

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Crew 3");
        setChoice(playerA, "Sulu, Heroic Helmsman");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertTapped("Sulu, Heroic Helmsman", true);
        assertType("Skysovereign, Consul Flagship", mage.constants.CardType.CREATURE, true);
    }
}
