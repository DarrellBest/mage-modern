package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class HMSBountyUnlikelyTimeshipTest extends CardTestPlayerBase {

    // When H.M.S. Bounty enters, you may search your library for a Whale card, reveal it, put it into your hand, then shuffle.
    @Test
    public void test_SearchesForAWhale() {
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.HAND, playerA, "H.M.S. Bounty, Unlikely Timeship");
        addCard(Zone.LIBRARY, playerA, "Colossal Whale");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "H.M.S. Bounty, Unlikely Timeship");
        setChoice(playerA, true);
        addTarget(playerA, "Colossal Whale");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Colossal Whale", 1);
    }

    // STATION 8+: Whenever it deals combat damage to a player, draw a card. If you control a Whale and haven't begun
    // an extra turn this game, take an extra turn after this one.
    @Test
    public void test_WhaleGivesExtraTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "H.M.S. Bounty, Unlikely Timeship");
        addCard(Zone.BATTLEFIELD, playerA, "Colossal Whale"); // power 5
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");     // power 3

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Colossal Whale");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Station");
        setChoice(playerA, "Hill Giant");
        attack(3, playerA, "H.M.S. Bounty, Unlikely Timeship");

        setStrictChooseMode(true);
        setStopAt(4, PhaseStep.UPKEEP); // turn 4 is A's extra turn
        execute();

        assertLife(playerB, 20 - 3);
        assertActivePlayer(playerA);
    }
}
