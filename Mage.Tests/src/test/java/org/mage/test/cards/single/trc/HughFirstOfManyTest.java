package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class HughFirstOfManyTest extends CardTestPlayerBase {

    @Test
    public void test_ArtifactSpellReplicates() {
        addCard(Zone.BATTLEFIELD, playerA, "Hugh, First of Many");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Ornithopter"); // {0}

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Ornithopter");
        setChoice(playerA, true);  // pay replicate
        setChoice(playerA, false); // only once

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Ornithopter", 2);
    }
}
