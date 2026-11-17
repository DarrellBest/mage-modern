package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class JadziaDaxLivingLegacyTest extends CardTestPlayerBase {

    private static final String JADZIA = "Jadzia Dax, Living Legacy";

    @Test
    public void test_CounterThenGreenMana() {
        addCard(Zone.BATTLEFIELD, playerA, JADZIA);
        addCard(Zone.HAND, playerA, "Grizzly Bears");

        // first main phase: one counter -> {G}; turn 3: two counters -> {G}{G}, enough for the Bears
        waitStackResolved(3, PhaseStep.PRECOMBAT_MAIN); // the trigger adds the mana
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, JADZIA, CounterType.P1P1, 2);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }

    @Test
    public void test_TransferSymbiontFromHand() {
        addCard(Zone.HAND, playerA, JADZIA);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "<i>Transfer Symbiont</i>", "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 2);
        assertGraveyardCount(playerA, JADZIA, 1);
    }
}
