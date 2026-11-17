package org.mage.test.cards.single.trc;

import mage.abilities.keyword.MenaceAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ExtractionIntegrantTest extends CardTestPlayerBase {

    @Test
    public void test_BlockersShrink() {
        addCard(Zone.BATTLEFIELD, playerA, "Extraction Integrant");
        addCard(Zone.BATTLEFIELD, playerA, "Bottle Gnomes"); // 1/3 artifact creature
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Llanowar Elves");

        attack(1, playerA, "Bottle Gnomes");
        block(1, playerB, "Grizzly Bears", "Bottle Gnomes");
        block(1, playerB, "Llanowar Elves", "Bottle Gnomes");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertAbility(playerA, "Bottle Gnomes", new MenaceAbility(false), true);
        // each blocker got -1/-1: Llanowar Elves (1/1) died, Grizzly Bears is 1/1 until end of turn
        assertGraveyardCount(playerB, "Llanowar Elves", 1);
        assertLife(playerB, 20);
    }
}
