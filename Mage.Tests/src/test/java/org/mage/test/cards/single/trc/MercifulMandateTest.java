package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class MercifulMandateTest extends CardTestPlayerBase {

    // Untap all creatures you control. If it's a combat phase, you may end the combat phase.
    private static final String MANDATE = "Merciful Mandate";

    @Test
    public void test_EndsCombatAndUntaps() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, MANDATE);

        attack(1, playerA, "Grizzly Bears");
        castSpell(1, PhaseStep.DECLARE_ATTACKERS, playerA, MANDATE);
        setChoice(playerA, true); // end the combat phase

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20);
        assertTapped("Grizzly Bears", false);
        assertExileCount(playerA, MANDATE, 1); // ending the combat phase exiles the stack, this spell included
    }

    @Test
    public void test_OutsideCombatJustUntaps() {
        addCard(Zone.BATTLEFIELD, playerA, "Llanowar Elves");
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.HAND, playerA, MANDATE);

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {G}");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, MANDATE);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertTapped("Llanowar Elves", false);
        assertGraveyardCount(playerA, MANDATE, 1);
    }
}
