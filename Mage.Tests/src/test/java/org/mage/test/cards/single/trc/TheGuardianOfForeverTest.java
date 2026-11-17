package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheGuardianOfForeverTest extends CardTestPlayerBase {

    // {4}, Sacrifice another nontoken creature: Return that card to the battlefield under its owner's control
    // at the beginning of the next end step.
    private static final String GUARDIAN = "The Guardian of Forever";

    @Test
    public void test_ReturnsAtEndStep() {
        addCard(Zone.BATTLEFIELD, playerA, GUARDIAN);
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{4}, Sacrifice");
        setChoice(playerA, "Grizzly Bears");
        checkGraveyardCount("in graveyard first", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Grizzly Bears", 1);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertGraveyardCount(playerA, "Grizzly Bears", 0);
    }
}
