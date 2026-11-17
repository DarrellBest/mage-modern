package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheInnerLightTest extends CardTestPlayerBase {

    @Test
    public void test_ReanimateThenDrawOnHit() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "The Inner Light");
        addCard(Zone.GRAVEYARD, playerA, "Hill Giant"); // red: not a legal target
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "The Inner Light");
        addTarget(playerA, "Grizzly Bears");
        addTarget(playerA, "Grizzly Bears"); // II on turn 3
        attack(3, playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertLife(playerB, 20 - 2);
        assertHandCount(playerA, 1 + 2); // turn 3 draw + 2 from the hit
    }
}
