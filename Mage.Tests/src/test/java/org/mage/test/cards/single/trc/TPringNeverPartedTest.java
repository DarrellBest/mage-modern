package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TPringNeverPartedTest extends CardTestPlayerBase {

    @Test
    public void test_FightAndDrawForEachDeath() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "T'Pring, Never Parted");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // 2/2
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears"); // 2/2: both die

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "T'Pring, Never Parted");
        addTarget(playerA, "Grizzly Bears"); // yours
        addTarget(playerA, "Grizzly Bears"); // theirs
        setChoice(playerA, true);            // fight
        setChoice(playerA, "Whenever a creature that fought this way dies"); // both died: order the two draws

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Grizzly Bears", 1);
        assertGraveyardCount(playerB, "Grizzly Bears", 1);
        assertHandCount(playerA, 2); // one card per creature that died
    }
}
