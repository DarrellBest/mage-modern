package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class FirstContactTest extends CardTestPlayerBase {

    @Test
    public void test_FirstSpellGainsLife() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.HAND, playerA, "First Contact");
        addCard(Zone.LIBRARY, playerA, "Forest", 3);
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "First Contact");
        setChoice(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Grizzly Bears", 1);
        assertLife(playerA, 22);
    }

    @Test
    public void test_NotFirstSpellNoLife() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.HAND, playerA, "First Contact");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "First Contact");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20);
    }
}
