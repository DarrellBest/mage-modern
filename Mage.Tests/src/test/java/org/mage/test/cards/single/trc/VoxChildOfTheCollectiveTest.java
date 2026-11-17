package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class VoxChildOfTheCollectiveTest extends CardTestPlayerBase {

    @Test
    public void test_NextArtifactCreatureHasUndaunted() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 7);
        addCard(Zone.HAND, playerA, "Vox, Child of the Collective"); // {4}{U}
        addCard(Zone.HAND, playerA, "Bottle Gnomes", 2);             // {3}: costs {2} with undaunted (one opponent)

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Vox, Child of the Collective");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Bottle Gnomes"); // 2 Islands left: only with the discount
        checkPlayableAbility("only the next one", 1, PhaseStep.END_TURN, playerA, "Cast Bottle Gnomes", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Bottle Gnomes", 1);
    }
}
