package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SSBotanyBayTest extends CardTestPlayerBase {

    // {T}: Each opponent exiles a card from their graveyard. You may exile a card from your graveyard.
    // STATION 8+: Flying. Whenever S.S. Botany Bay deals combat damage to a player, return each creature card you own
    // exiled with it to the battlefield. 3/5
    private static final String SHIP = "S.S. Botany Bay";

    @Test
    public void test_ExileThenReturnOwnCreature() {
        addCard(Zone.BATTLEFIELD, playerA, SHIP);
        addCard(Zone.BATTLEFIELD, playerA, "Craw Wurm"); // power 6
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // power 2
        addCard(Zone.GRAVEYARD, playerA, "Hill Giant");
        addCard(Zone.GRAVEYARD, playerB, "Lightning Bolt");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Each opponent exiles");
        setChoice(playerB, "Lightning Bolt");
        setChoice(playerA, true);
        setChoice(playerA, "Hill Giant");
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Craw Wurm");
        waitStackResolved(1, PhaseStep.POSTCOMBAT_MAIN);
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Grizzly Bears");

        attack(3, playerA, SHIP);

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 3);
        assertPermanentCount(playerA, "Hill Giant", 1);
        assertExileCount(playerB, "Lightning Bolt", 1); // not yours: stays exiled
    }
}
