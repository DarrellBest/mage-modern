package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SinsOfTheFatherTest extends CardTestPlayerBase {

    @Test
    public void test_EdictThenKlingons() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.HAND, playerA, "Sins of the Father");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sins of the Father");
        setChoice(playerB, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerB, "Grizzly Bears", 1);
        assertPermanentCount(playerB, "Hill Giant", 1);
        assertPermanentCount(playerA, "Klingon Warrior Token", 1);
    }

    @Test
    public void test_ChapterThreeDrains() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 2);
        addCard(Zone.HAND, playerA, "Sins of the Father");
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears", 2);
        addCard(Zone.GRAVEYARD, playerB, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Sins of the Father");
        addTarget(playerA, playerB); // III on turn 5

        setStrictChooseMode(true);
        setStopAt(5, PhaseStep.END_TURN);
        execute();

        assertExileCount(playerB, 3);
        assertLife(playerB, 20 - 2);
    }
}
