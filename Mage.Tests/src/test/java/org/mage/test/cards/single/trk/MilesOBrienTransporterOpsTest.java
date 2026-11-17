package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class MilesOBrienTransporterOpsTest extends CardTestPlayerBase {

    @Test
    public void test_BounceThenDrawAtEndStep() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 3);
        addCard(Zone.HAND, playerA, "Miles O'Brien, Transporter Ops");
        addCard(Zone.BATTLEFIELD, playerA, "Ornithopter");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Miles O'Brien, Transporter Ops");
        addTarget(playerA, "Ornithopter");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        // Ornithopter returned + 1 card drawn at the end step
        assertHandCount(playerA, "Ornithopter", 1);
        assertHandCount(playerA, 2);
    }
}
