package org.mage.test.cards.single.trk;

import mage.abilities.keyword.IndestructibleAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheNeedsOfTheManyTest extends CardTestPlayerBase {

    @Test
    public void test_ProtectsAndExilesItself() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, "The Needs of the Many");
        addCard(Zone.BATTLEFIELD, playerA, "Ornithopter");   // sacrificed
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "The Needs of the Many");
        setChoice(playerA, "Ornithopter");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Ornithopter", 1);
        assertExileCount(playerA, "The Needs of the Many", 1);
        assertAbility(playerA, "Grizzly Bears", IndestructibleAbility.getInstance(), true);
    }
}
