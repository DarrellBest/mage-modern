package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class JulianBashirFrontierDoctorTest extends CardTestPlayerBase {

    // Whenever Julian Bashir attacks, return target creature card with mana value X or less from your graveyard to
    // the battlefield, where X is Julian Bashir's power.
    @Test
    public void test_AttackReturnsSmallCreature() {
        addCard(Zone.BATTLEFIELD, playerA, "Julian Bashir, Frontier Doctor"); // 1/1
        addCard(Zone.GRAVEYARD, playerA, "Llanowar Elves"); // mana value 1
        addCard(Zone.GRAVEYARD, playerA, "Grizzly Bears");  // mana value 2: too big

        attack(1, playerA, "Julian Bashir, Frontier Doctor");
        addTarget(playerA, "Llanowar Elves");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Llanowar Elves", 1);
        assertGraveyardCount(playerA, "Grizzly Bears", 1);
    }
}
