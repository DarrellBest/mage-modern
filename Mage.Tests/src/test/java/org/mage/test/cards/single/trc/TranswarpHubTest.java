package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TranswarpHubTest extends CardTestPlayerBase {

    // When this Spacecraft enters, mill five cards. You may put up to two artifact cards from among them into your hand.
    // STATION 3+: {T}: Target creature can't be blocked this turn.
    private static final String HUB = "Transwarp Hub";

    @Test
    public void test_MillAndTakeTwoArtifacts() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, HUB);
        addCard(Zone.LIBRARY, playerA, "Forest", 3);
        addCard(Zone.LIBRARY, playerA, "Ornithopter");
        addCard(Zone.LIBRARY, playerA, "Bottle Gnomes");
        addCard(Zone.LIBRARY, playerA, "Grizzly Bears");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, HUB);
        setChoice(playerA, "Ornithopter^Bottle Gnomes");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Ornithopter", 1);
        assertHandCount(playerA, "Bottle Gnomes", 1);
        assertGraveyardCount(playerA, "Grizzly Bears", 1);
    }

    @Test
    public void test_StationedMakesUnblockable() {
        addCard(Zone.BATTLEFIELD, playerA, HUB);
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant"); // power 3: station it
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Wall of Wood");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Hill Giant");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Target creature can't be blocked", "Grizzly Bears");
        attack(1, playerA, "Grizzly Bears");
        block(1, playerB, "Wall of Wood", "Grizzly Bears");

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 2);
    }
}
