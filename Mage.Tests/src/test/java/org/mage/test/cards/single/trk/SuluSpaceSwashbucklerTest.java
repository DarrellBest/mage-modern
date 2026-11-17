package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SuluSpaceSwashbucklerTest extends CardTestPlayerBase {

    @Test
    public void test_DrawsAfterSpacecraftEntered() {
        addCard(Zone.BATTLEFIELD, playerA, "Sulu, Space Swashbuckler");
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 3);
        addCard(Zone.HAND, playerA, "U.S.S. Stargazer, Here and There");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "U.S.S. Stargazer, Here and There");
        attack(1, playerA, "Sulu, Space Swashbuckler"); // becomes tapped

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 1);
    }

    @Test
    public void test_NoSpacecraftNoDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "Sulu, Space Swashbuckler");

        attack(1, playerA, "Sulu, Space Swashbuckler");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, 0);
    }
}
