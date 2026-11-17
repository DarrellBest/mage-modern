package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class CombadgeTest extends CardTestPlayerBase {

    @Test
    public void test_CastCopiesItself() {
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.HAND, playerA, "Combadge");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Combadge");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Combadge", 2);
    }

    @Test
    public void test_TwoCommunicatorsMakeUnblockable() {
        addCard(Zone.BATTLEFIELD, playerA, "Combadge@cb1");
        addCard(Zone.BATTLEFIELD, playerA, "Combadge@cb2");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 4);
        addCard(Zone.BATTLEFIELD, playerB, "Wall of Wood");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "@cb1 Equip", "Grizzly Bears");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "@cb2 Equip", "Hill Giant");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        attack(1, playerA, "Hill Giant");
        block(1, playerB, "Wall of Wood", "Hill Giant");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
    }
}
