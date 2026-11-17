package org.mage.test.cards.single.trc;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class RegBarclayFidgetyFantasistTest extends CardTestPlayerBase {

    @Test
    public void test_SecondDrawMakesIllusionCopy() {
        addCard(Zone.BATTLEFIELD, playerA, "Reg Barclay, Fidgety Fantasist");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Divination"); // turn 3: draw step is the 1st, Divination the 2nd and 3rd

        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Divination");
        addTarget(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 2);
        assertType("Hill Giant", CardType.ARTIFACT, SubType.ILLUSION);
    }
}
