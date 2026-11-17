package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class MaroonedTest extends CardTestPlayerBase {

    // Enchanted permanent doesn't untap during its controller's untap step and can't be beamed up.
    private static final String BOARDING = "Boarding Action"; // Beam me up {2}{R}

    @Test
    public void test_CantBeBeamedUp() {
        addCard(Zone.BATTLEFIELD, playerA, "Island");
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.HAND, playerA, "Marooned");
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears"); // the only creature
        addCard(Zone.GRAVEYARD, playerA, BOARDING);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Marooned", "Grizzly Bears");
        setChoice(playerA, false); // don't pay {3} to tap it
        checkPlayableAbility("no creature can pay beam me up", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Beam me up {2}{R}", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void test_ControlWithoutMaroonedItCanBeBeamedUp() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.GRAVEYARD, playerA, BOARDING);

        checkPlayableAbility("can pay beam me up", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Beam me up {2}{R}", true);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }

    @Test
    public void test_PayToTapAndItStaysTapped() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 4);
        addCard(Zone.HAND, playerA, "Marooned");
        addCard(Zone.BATTLEFIELD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Marooned", "Grizzly Bears");
        setChoice(playerA, true); // pay {3}

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.PRECOMBAT_MAIN);
        execute();

        assertTapped("Grizzly Bears", true); // didn't untap in B's untap step
    }
}
