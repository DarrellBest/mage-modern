package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheMeasureOfAManTest extends CardTestPlayerBase {

    @Test
    public void test_ExileInvestigateReturn() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 3);
        addCard(Zone.HAND, playerA, "The Measure of a Man");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "The Measure of a Man");
        addTarget(playerA, "Grizzly Bears");
        checkExileCount("exiled", 1, PhaseStep.END_TURN, playerB, "Grizzly Bears", 1);
        checkPermanentCount("clue", 3, PhaseStep.END_TURN, playerA, "Clue Token", 1);

        setStrictChooseMode(true);
        setStopAt(5, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerB, "Grizzly Bears", 1);
        assertLife(playerA, 22);
        assertGraveyardCount(playerA, "The Measure of a Man", 1);
    }
}
