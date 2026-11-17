package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class KaiWinnFaithlessLeaderTest extends CardTestPlayerBase {

    @Test
    public void test_SpellAndAbilityWithChosenName() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        addCard(Zone.HAND, playerA, "Kai Winn, Faithless Leader");
        addCard(Zone.BATTLEFIELD, playerB, "Mountain", 4);
        addCard(Zone.HAND, playerB, "Lightning Bolt");
        addCard(Zone.BATTLEFIELD, playerB, "Rod of Ruin");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Kai Winn, Faithless Leader");
        setChoice(playerA, "Lightning Bolt");
        castSpell(2, PhaseStep.PRECOMBAT_MAIN, playerB, "Lightning Bolt", playerA);
        // Rod of Ruin isn't the chosen name: no trigger
        activateAbility(2, PhaseStep.POSTCOMBAT_MAIN, playerB, "{3}, {T}: ", playerA);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertLife(playerA, 20 - 3 - 1);
    }
}
