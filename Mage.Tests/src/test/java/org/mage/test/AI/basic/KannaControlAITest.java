package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * DARRELLBEST-FORK: control test using the STOCK AI (no Kanna override) with
 * the exact same scenario as KannaSanityAITest, to isolate whether that
 * failure is Kanna-specific or just how this scenario behaves in general.
 */
public class KannaControlAITest extends CardTestPlayerBaseAI {

    @Test
    public void test_StockAI_PlaysLandAndCastsCreature() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Grizzly Bears", 1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Forest", 2);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }
}
