package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ScottyMiracleWorkerTest extends CardTestPlayerBase {

    private static final String SCOTTY = "Scotty, Miracle Worker";

    @Test
    public void test_GraveyardSpellHasBeamMeUp() {
        addCard(Zone.BATTLEFIELD, playerA, SCOTTY);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.GRAVEYARD, playerA, "Lightning Bolt");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Beam me up {R}", playerB);
        setChoice(playerA, "Grizzly Bears"); // return it to pay beam me up

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertHandCount(playerA, "Grizzly Bears", 1);
        assertExileCount(playerA, "Lightning Bolt", 1);
    }

    @Test
    public void test_ScottyCantPayTheCost() {
        addCard(Zone.BATTLEFIELD, playerA, SCOTTY); // the only creature
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.GRAVEYARD, playerA, "Lightning Bolt");

        checkPlayableAbility("only Scotty to return", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Beam me up {R}", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
