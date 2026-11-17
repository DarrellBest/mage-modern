package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheWrathOfKhanTest extends CardTestPlayerBase {

    // I -- 4 damage to target creature an opponent controls. At the beginning of the next combat this turn, gain
    // control of that creature until end of turn. Untap it. It gains haste until end of turn.
    @Test
    public void test_DamageThenBorrowIt() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.HAND, playerA, "The Wrath of Khan");
        addCard(Zone.BATTLEFIELD, playerB, "Ironroot Treefolk"); // 3/5 survives the 4 damage

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "The Wrath of Khan");
        addTarget(playerA, "Ironroot Treefolk");
        attack(1, playerA, "Ironroot Treefolk");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 3);
        assertPermanentCount(playerB, "Ironroot Treefolk", 1); // control went back at end of turn
    }
}
