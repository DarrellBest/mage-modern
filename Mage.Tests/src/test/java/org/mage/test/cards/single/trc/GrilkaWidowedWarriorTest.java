package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GrilkaWidowedWarriorTest extends CardTestPlayerBase {

    @Test
    public void test_StealAttackMakeTreasures() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 6);
        addCard(Zone.HAND, playerA, "Grilka, Widowed Warrior");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Grilka, Widowed Warrior");
        addTarget(playerA, "Hill Giant");
        attack(1, playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 3);
        assertPermanentCount(playerA, "Treasure Token", 3);
        assertPermanentCount(playerB, "Hill Giant", 1); // back at end of turn
    }
}
