package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestCommanderDuelBase;

public class QueensDiamondTest extends CardTestCommanderDuelBase {

    // {T}: Choose a color. Add one mana of that color for each charge counter on Queen's Diamond.
    // Spend this mana only to cast your commander.
    private static final String DIAMOND = "Queen's Diamond";

    @Test
    public void test_PaysForCommanderOnly() {
        addCard(Zone.COMMAND, playerA, "Hill Giant"); // {3}{R}
        addCard(Zone.BATTLEFIELD, playerA, DIAMOND);
        addCard(Zone.BATTLEFIELD, playerA, "Craw Wurm"); // power 6 -> 6 charge counters
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Craw Wurm");
        checkPlayableAbility("not for other spells", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Cast Lightning Bolt", false);
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Hill Giant");
        setChoice(playerA, "Red");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
    }
}
