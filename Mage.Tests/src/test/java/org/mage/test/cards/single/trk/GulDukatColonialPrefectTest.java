package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GulDukatColonialPrefectTest extends CardTestPlayerBase {

    // At the beginning of each end step, each opponent loses life equal to the life that player lost this turn.
    // You gain life equal to the life lost this way.
    private static final String DUKAT = "Gul Dukat, Colonial Prefect";

    @Test
    public void test_DoublesOpponentsLifeLoss() {
        addCard(Zone.BATTLEFIELD, playerA, DUKAT);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.UPKEEP); // turn 2's end step: B lost nothing that turn
        execute();

        assertLife(playerB, 20 - 3 - 3);
        assertLife(playerA, 20 + 3);
    }
}
