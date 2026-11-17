package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GwyndalaTheProgenyTest extends CardTestPlayerBase {

    @Test
    public void test_EntersEquipped() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, "Gwyndala, the Progeny");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Gwyndala, the Progeny");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Neuroflux Heirloom", 1);
        assertPowerToughness(playerA, "Gwyndala, the Progeny", 2, 2);
    }
}
