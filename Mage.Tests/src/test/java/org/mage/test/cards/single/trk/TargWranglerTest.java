package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TargWranglerTest extends CardTestPlayerBase {

    // Menace. At the beginning of your end step, if this creature attacked this turn, sacrifice a nonland permanent.
    private static final String TARG = "Targ Wrangler";

    @Test
    public void test_AttackedThenSacrifices() {
        addCard(Zone.BATTLEFIELD, playerA, TARG);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        attack(1, playerA, TARG);
        setChoice(playerA, "Grizzly Bears"); // the nonland permanent to sacrifice

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 5);
        assertGraveyardCount(playerA, "Grizzly Bears", 1);
        assertPermanentCount(playerA, TARG, 1);
    }

    @Test
    public void test_DidNotAttackNoSacrifice() {
        addCard(Zone.BATTLEFIELD, playerA, TARG);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertPermanentCount(playerA, TARG, 1);
    }
}
