package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheHolodeckTest extends CardTestPlayerBase {

    @Test
    public void test_OncePerTurnFromGraveyard() {
        addCard(Zone.BATTLEFIELD, playerA, "The Holodeck");
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 2);
        addCard(Zone.GRAVEYARD, playerA, "Swamp");
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears");

        playLand(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Swamp");
        checkPlayableAbility("used this turn", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Cast Grizzly Bears", false);
        castSpell(3, PhaseStep.PRECOMBAT_MAIN, playerA, "Grizzly Bears"); // a new turn

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Swamp", 1);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }
}
