package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class HoloEngineerTest extends CardTestPlayerBase {

    // When this creature enters, create a 3/3 colorless Illusion artifact creature token.
    // If one or more tokens would be created under your control, that many 3/3 Illusion artifact creature tokens are created instead.
    @Test
    public void test_ReplacesAllTokens() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Holo-Engineer");
        addCard(Zone.HAND, playerA, "Raise the Alarm"); // two Soldiers -> two Illusions

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Holo-Engineer");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Raise the Alarm");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Illusion Token", 3);
        assertPermanentCount(playerA, "Soldier Token", 0);
    }
}
