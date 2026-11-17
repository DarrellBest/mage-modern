package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TellMeWhatYouSeeTest extends CardTestPlayerBase {

    // When this enchantment enters, target opponent may have you draw a card. If they don't, they lose 4 life and
    // you surveil 1. Whenever you face a dilemma, you may return this enchantment to its owner's hand.
    private static final String TMWYS = "Tell Me What You See";

    @Test
    public void test_OpponentRefusesTheDraw() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        addCard(Zone.HAND, playerA, TMWYS);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TMWYS);
        addTarget(playerA, playerB);
        setChoice(playerB, false); // don't let A draw
        addTarget(playerA, TestPlayer.TARGET_SKIP); // surveil: keep the card on top

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 4);
        assertHandCount(playerA, 0);
    }

    @Test
    public void test_OpponentLetsYouDraw() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 2);
        addCard(Zone.HAND, playerA, TMWYS);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, TMWYS);
        addTarget(playerA, playerB);
        setChoice(playerB, true);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20);
        assertHandCount(playerA, 1);
    }

    @Test
    public void test_DilemmaReturnsItToHand() {
        addCard(Zone.BATTLEFIELD, playerA, TMWYS);
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Healing Salve");

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Healing Salve", playerA);
        setChoice(playerA, true); // return it

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, TMWYS, 1);
    }
}
