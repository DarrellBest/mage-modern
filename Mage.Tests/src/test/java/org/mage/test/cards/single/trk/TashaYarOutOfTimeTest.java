package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TashaYarOutOfTimeTest extends CardTestPlayerBase {

    private static final String TASHA = "Tasha Yar, Out of Time";

    @Test
    public void test_GraveyardTokenCopyIsSacrificed() {
        addCard(Zone.GRAVEYARD, playerA, TASHA);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{2}{R}, Exile this card");
        attack(1, playerA, TASHA); // the token has haste

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertLife(playerB, 20 - 2);
        assertExileCount(playerA, TASHA, 1);
        assertPermanentCount(playerA, TASHA, 0); // sacrificed at end step
    }
}
