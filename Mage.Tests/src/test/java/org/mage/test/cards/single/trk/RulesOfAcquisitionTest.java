package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class RulesOfAcquisitionTest extends CardTestPlayerBase {

    @Test
    public void test_ThirdRuleStealAndOpponentDraws() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.HAND, playerA, "Rules of Acquisition");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Rules of Acquisition", "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertHandCount(playerB, 3);
    }

    @Test
    public void test_NinetyEighthRuleGiveAwayAndDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.HAND, playerA, "Rules of Acquisition");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        setModeChoice(playerA, "2");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Rules of Acquisition");
        addTarget(playerA, playerB);
        addTarget(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerB, "Grizzly Bears", 1);
        assertHandCount(playerA, 3);
    }
}
