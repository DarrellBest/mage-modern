package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class NogAmbitiousCadetTest extends CardTestPlayerBase {

    private static final String NOG = "Nog, Ambitious Cadet";

    @Test
    public void test_RogueToScoutToOfficer() {
        addCard(Zone.BATTLEFIELD, playerA, NOG);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.GRAVEYARD, playerA, "Forest");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{1}: If");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{3}: If");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        playLand(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Forest"); // from the graveyard

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertSubtype(NOG, SubType.OFFICER);
        assertNotSubtype(NOG, SubType.ROGUE);
        assertNotSubtype(NOG, SubType.SCOUT);
        assertPowerToughness(playerA, NOG, 3, 4);
        assertPermanentCount(playerA, "Forest", 1);
    }

    @Test
    public void test_OfficerNeedsScoutFirst() {
        addCard(Zone.BATTLEFIELD, playerA, NOG);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{3}: If"); // still a Rogue: nothing happens

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertSubtype(NOG, SubType.ROGUE);
        assertPowerToughness(playerA, NOG, 2, 2);
    }
}
