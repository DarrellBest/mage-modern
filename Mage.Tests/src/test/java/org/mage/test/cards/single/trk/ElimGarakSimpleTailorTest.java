package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ElimGarakSimpleTailorTest extends CardTestPlayerBase {

    @Test
    public void test_FacingADilemma() {
        addCard(Zone.BATTLEFIELD, playerA, "Elim Garak, Simple Tailor");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Healing Salve");

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Healing Salve", playerA);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 2);
        assertLife(playerA, 20 + 3 + 2);
    }
}
