package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class NyotaUhuraCommunicationsTest extends CardTestPlayerBase {

    @Test
    public void test_DrawWhenOpponentCastsChosenManaValue() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Nyota Uhura, Communications");
        addCard(Zone.BATTLEFIELD, playerB, "Mountain", 5);
        addCard(Zone.HAND, playerB, "Lightning Bolt"); // mana value 1: draws
        addCard(Zone.HAND, playerB, "Hill Giant");     // mana value 4: doesn't

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Nyota Uhura, Communications");
        setChoiceAmount(playerA, 1);
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Lightning Bolt", playerA);
        castSpell(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 1);
    }
}
