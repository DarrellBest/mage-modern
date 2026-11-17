package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class OverflowSubmatrixTest extends CardTestPlayerBase {

    @Test
    public void test_CastFromGraveyardEntersWithFinality() {
        addCard(Zone.BATTLEFIELD, playerA, "Overflow Submatrix");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.GRAVEYARD, playerA, "Bottle Gnomes");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Bottle Gnomes");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Bottle Gnomes", 1);
        assertCounterCount(playerA, "Bottle Gnomes", CounterType.FINALITY, 1);
    }

    @Test
    public void test_ControlCastFromHandNoFinality() {
        addCard(Zone.BATTLEFIELD, playerA, "Overflow Submatrix");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.HAND, playerA, "Bottle Gnomes");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Bottle Gnomes");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Bottle Gnomes", 1);
        assertCounterCount(playerA, "Bottle Gnomes", CounterType.FINALITY, 0);
    }
}
