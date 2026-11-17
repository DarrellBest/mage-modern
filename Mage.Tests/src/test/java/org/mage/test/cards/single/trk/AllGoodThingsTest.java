package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class AllGoodThingsTest extends CardTestPlayerBase {

    // I, II, III -- Draw two cards, then exile a card from your hand.
    // IV -- If all cards exiled with this Saga share a card type, you gain 10 life.
    @Test
    public void test_AllLandsGainTen() {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Forest", 20);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 1);
        addCard(Zone.HAND, playerA, "All Good Things...");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "All Good Things...");
        setChoice(playerA, "Forest"); // I
        setChoice(playerA, "Forest"); // II
        setChoice(playerA, "Forest"); // III

        setStrictChooseMode(true);
        setStopAt(7, PhaseStep.END_TURN);
        execute();

        assertExileCount(playerA, "Forest", 3);
        assertLife(playerA, 30);
        assertGraveyardCount(playerA, "All Good Things...", 1);
    }
}
