package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class BlastoneuronTest extends CardTestPlayerBase {

    @Test
    public void test_ExileCopyHasteAndDrain() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.HAND, playerA, "Blastoneuron");
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Blastoneuron");
        addTarget(playerA, "Grizzly Bears");
        attack(1, playerA, "Grizzly Bears"); // token copy has haste

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertExileCount(playerA, "Grizzly Bears", 1);
        assertLife(playerB, 20 - 1 - 2); // drain when it left the graveyard, then the attack
        assertLife(playerA, 20 + 1);
        assertPermanentCount(playerA, "Grizzly Bears", 0); // token sacrificed at end step
    }
}
