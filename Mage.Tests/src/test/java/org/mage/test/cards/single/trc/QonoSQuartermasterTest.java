package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class QonoSQuartermasterTest extends CardTestPlayerBase {

    // Menace. Whenever this creature attacks, add one mana of any color.
    // Until end of turn, you don't lose this mana as steps and phases end.
    private static final String QM = "Qo'noS Quartermaster";

    @Test
    public void test_ManaLastsUntilPostcombatMain() {
        addCard(Zone.BATTLEFIELD, playerA, QM);
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        attack(1, playerA, QM);
        setChoice(playerA, "Red");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 2 - 3);
        assertGraveyardCount(playerA, "Lightning Bolt", 1);
    }
}
