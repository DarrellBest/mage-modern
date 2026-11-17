package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ThoseOldScientistsTest extends CardTestPlayerBase {

    @Test
    public void test_LootThenRegrowArtifact() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Those Old Scientists");
        addCard(Zone.LIBRARY, playerA, "Forest", 5);
        addCard(Zone.LIBRARY, playerA, "Lightning Bolt");
        addCard(Zone.LIBRARY, playerA, "Ornithopter"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Those Old Scientists"); // I: hand is exactly the two drawn cards, so both are discarded
        addTarget(playerA, "Ornithopter");               // II (turn 3): return it

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Ornithopter", 1);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }
}
