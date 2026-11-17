package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class CaptainBenjaminSiskoTest extends CardTestPlayerBase {

    @Test
    public void test_FacingADilemma() {
        addCard(Zone.BATTLEFIELD, playerA, "Captain Benjamin Sisko");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Healing Salve");
        addCard(Zone.HAND, playerA, "Healing Salve");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Healing Salve", playerA);
        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Healing Salve", playerA);
        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP); // end step trigger resolves
        execute();

        assertPermanentCount(playerA, "Officer Token", 2);
    }
}
