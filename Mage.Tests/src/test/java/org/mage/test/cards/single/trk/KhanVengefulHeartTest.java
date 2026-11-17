package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class KhanVengefulHeartTest extends CardTestPlayerBase {

    private static final String KHAN = "Khan, Vengeful Heart";

    @Test
    public void test_SacrificeToDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.HAND, playerA, KHAN);
        addCard(Zone.BATTLEFIELD, playerA, "Ornithopter");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, KHAN);
        setChoice(playerA, true);
        setChoice(playerA, "Ornithopter");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Ornithopter", 1);
        assertHandCount(playerA, 2);
    }

    @Test
    public void test_CastFromExile() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.EXILED, playerA, KHAN);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, KHAN);
        // nothing else to sacrifice, so no "you may" prompt

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, KHAN, 1);
    }
}
