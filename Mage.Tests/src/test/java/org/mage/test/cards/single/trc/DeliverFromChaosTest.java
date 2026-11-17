package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DeliverFromChaosTest extends CardTestPlayerBase {

    // Exile target artifact or creature. Create X 1/1 colorless Borg artifact creature tokens, where X is that permanent's mana value.
    @Test
    public void test_ExileAndBorgTokens() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1);
        addCard(Zone.HAND, playerA, "Deliver from Chaos");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant"); // mana value 4

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Deliver from Chaos", "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertExileCount(playerB, "Hill Giant", 1);
        assertPermanentCount(playerA, "Borg Token", 4);
    }
}
