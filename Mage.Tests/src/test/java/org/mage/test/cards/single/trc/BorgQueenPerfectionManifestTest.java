package org.mage.test.cards.single.trc;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * Also covers the fork's assimilate core (701.72): a card from a graveyard enters under your control with a +1/+1
 * counter as a Borg artifact creature that lost its other creature types.
 */
public class BorgQueenPerfectionManifestTest extends CardTestPlayerBase {

    // Artifact creatures you control get +2/+0. When Borg Queen enters, assimilate target creature card from an
    // opponent's graveyard.
    private static final String QUEEN = "Borg Queen, Perfection Manifest";

    @Test
    public void test_AssimilatesFromOpponentsGraveyard() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 6);
        addCard(Zone.HAND, playerA, QUEEN);
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, QUEEN);
        addTarget(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertCounterCount(playerA, "Grizzly Bears", CounterType.P1P1, 1);
        assertType("Grizzly Bears", CardType.ARTIFACT, true);
        assertSubtype("Grizzly Bears", SubType.BORG);
        assertNotSubtype("Grizzly Bears", SubType.BEAR);
        assertPowerToughness(playerA, "Grizzly Bears", 2 + 1 + 2, 2 + 1); // +2/+0 from the Queen
        assertPowerToughness(playerA, QUEEN, 1 + 2, 4); // she's an artifact creature too
    }
}
