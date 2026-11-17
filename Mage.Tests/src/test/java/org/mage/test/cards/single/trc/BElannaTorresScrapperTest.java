package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class BElannaTorresScrapperTest extends CardTestPlayerBase {

    private static final String BELANNA = "B'Elanna Torres, Scrapper";

    @Test
    public void test_ArtifactOnTopDraws() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, BELANNA);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.LIBRARY, playerA, "Forest", 2);
        addCard(Zone.LIBRARY, playerA, "Ornithopter"); // top

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}, {T}: Surveil 2");
        addTarget(playerA, TestPlayer.TARGET_SKIP); // surveil: keep both on top
        setChoice(playerA, "Forest");               // order: Forest first, so Ornithopter ends on top
        setChoice(playerA, true);                   // reveal

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Ornithopter", 1);
        assertCounterCount(playerA, BELANNA, CounterType.P1P1, 0);
    }

    @Test
    public void test_NoRevealGetsCounters() {
        addCard(Zone.BATTLEFIELD, playerA, BELANNA);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}, {T}: Surveil 2");
        setChoice(playerA, false); // don't reveal

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, BELANNA, CounterType.P1P1, 2);
    }
}
