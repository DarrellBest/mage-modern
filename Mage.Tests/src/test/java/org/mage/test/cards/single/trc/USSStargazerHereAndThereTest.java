package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class USSStargazerHereAndThereTest extends CardTestPlayerBase {

    // STATION 6+: Flying. Picard Maneuver -- Whenever U.S.S. Stargazer attacks, create a tapped and attacking 4/3
    // colorless Spacecraft Illusion artifact creature token with flying. Exile that token at end of combat. 4/3
    private static final String SHIP = "U.S.S. Stargazer, Here and There";

    @Test
    public void test_PicardManeuver() {
        addCard(Zone.BATTLEFIELD, playerA, SHIP);
        addCard(Zone.BATTLEFIELD, playerA, "Craw Wurm"); // power 6

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Craw Wurm");
        attack(3, playerA, SHIP);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertLife(playerB, 20 - 4 - 4);
        assertPermanentCount(playerA, "Spacecraft Illusion Token", 0); // exiled at end of combat
    }
}
