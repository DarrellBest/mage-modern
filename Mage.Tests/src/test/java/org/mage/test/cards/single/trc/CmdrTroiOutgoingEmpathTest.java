package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class CmdrTroiOutgoingEmpathTest extends CardTestPlayerBase {

    @Test
    public void test_OpponentDeclinesSoYouDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "Cmdr. Troi, Outgoing Empath");
        addCard(Zone.HAND, playerB, "Lightning Bolt");

        activateAbility(2, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Target opponent whose turn it is", playerB);
        setChoice(playerB, false);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 1);
        assertHandCount(playerB, "Lightning Bolt", 1);
    }

    @Test
    public void test_OpponentCastsAndYouCopy() {
        addCard(Zone.BATTLEFIELD, playerA, "Cmdr. Troi, Outgoing Empath");
        addCard(Zone.HAND, playerB, "Lightning Bolt");

        activateAbility(2, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Target opponent whose turn it is", playerB);
        setChoice(playerB, true);
        addTarget(playerB, playerA);  // B bolts A
        setChoice(playerA, true);     // A changes the copy's target
        addTarget(playerA, playerB);

        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 - 3);
        assertLife(playerB, 20 - 3);
        assertHandCount(playerA, 0);
    }
}
