package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class EzriDaxNewlyJoinedTest extends CardTestPlayerBase {

    @Test
    public void test_CopiesCreatureThatDiedThisTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.HAND, playerA, "Ezri Dax, Newly Joined");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", "Hill Giant");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Ezri Dax, Newly Joined");
        setChoice(playerA, true);
        setChoice(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Ezri Dax, Newly Joined", 1);
        assertPowerToughness(playerA, "Ezri Dax, Newly Joined", 3, 3);
        assertSubtype("Ezri Dax, Newly Joined", SubType.GIANT);
        assertSubtype("Ezri Dax, Newly Joined", SubType.TRILL);
    }
}
