package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class TheCaretakerTest extends CardTestPlayerBase {

    // At the beginning of each end step, if you faced a dilemma this turn, target opponent exiles cards from the
    // top of their library until they exile a nonland card. You may cast that card without paying its mana cost.
    private static final String CARETAKER = "The Caretaker";

    @Test
    public void test_CastsOpponentsCardAfterADilemma() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, CARETAKER);
        addCard(Zone.BATTLEFIELD, playerA, "Plains");
        addCard(Zone.HAND, playerA, "Healing Salve");
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Grizzly Bears");
        addCard(Zone.LIBRARY, playerB, "Forest"); // on top: exiled first

        setModeChoice(playerA, "1");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Healing Salve", playerA);
        addTarget(playerA, playerB); // end step trigger
        setChoice(playerA, true);    // cast Grizzly Bears for free

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertExileCount(playerB, "Forest", 1);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }

    @Test
    public void test_NoDilemmaNoTrigger() {
        addCard(Zone.BATTLEFIELD, playerA, CARETAKER);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertExileCount(playerB, 0);
    }
}
