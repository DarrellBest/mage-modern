package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class GabrielLorcaMirrorMenaceTest extends CardTestPlayerBase {

    @Test
    public void test_FacingADilemma() {
        addCard(Zone.GRAVEYARD, playerA, "Gabriel Lorca, Mirror Menace");
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Healing Salve");
        addCard(Zone.BATTLEFIELD, playerA, "Swamp");
        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Healing Salve", playerA);
        setChoice(playerA, true); // pay {B}
        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Gabriel Lorca, Mirror Menace", 1);
        assertGraveyardCount(playerA, "Gabriel Lorca, Mirror Menace", 0);
    }
}
