package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DominionInfiltrationTest extends CardTestPlayerBase {

    @Test
    public void test_TwoHastyCopiesExiledAtEndStep() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        addCard(Zone.HAND, playerA, "Dominion Infiltration");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Dominion Infiltration");
        setChoiceAmount(playerA, 2); // X = 2
        addTarget(playerA, "Hill Giant^Grizzly Bears");
        attack(1, playerA, "Hill Giant");

        setChoice(playerA, "At the beginning of the next end step"); // order the two exile triggers
        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 3);
        assertPermanentCount(playerA, "Hill Giant", 0);
        assertPermanentCount(playerA, "Grizzly Bears", 0);
    }
}
