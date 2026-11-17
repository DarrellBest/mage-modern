package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GenesisDeviceTest extends CardTestPlayerBase {

    @Test
    public void test_EntersFetchesBasic() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, "Genesis Device");
        addCard(Zone.LIBRARY, playerA, "Island");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Genesis Device");
        addTarget(playerA, "Island");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Island", 1);
        assertTapped("Island", true);
    }

    @Test
    public void test_SacrificeLandsForThatMany() {
        addCard(Zone.BATTLEFIELD, playerA, "Genesis Device");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2);
        addCard(Zone.LIBRARY, playerA, "Island", 2);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{3}{G}{G}");
        setChoice(playerA, "Wastes^Wastes");
        addTarget(playerA, "Island^Island");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Wastes", 0);
        assertPermanentCount(playerA, "Island", 2);
        assertGraveyardCount(playerA, "Genesis Device", 1);
    }
}
