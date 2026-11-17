package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class PlanetKillerTest extends CardTestPlayerBase {

    // Whenever this creature deals combat damage to a player, put a +1/+1 counter on it for each land that player controls.
    private static final String PK = "Planet Killer";

    @Test
    public void test_CountersForDefendersLands() {
        addCard(Zone.BATTLEFIELD, playerA, PK);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5); // the attacker's lands don't count
        addCard(Zone.BATTLEFIELD, playerB, "Mountain", 3);

        // no attack() needed: it attacks each combat if able

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 9);
        assertPowerToughness(playerA, PK, 9 + 3, 9 + 3);
    }
}
