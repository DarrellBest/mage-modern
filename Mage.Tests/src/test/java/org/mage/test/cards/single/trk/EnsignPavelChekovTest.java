package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class EnsignPavelChekovTest extends CardTestPlayerBase {

    @Test
    public void test_PlayOpponentsExiledLand() {
        skipInitShuffling();
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1); // 2 differently named lands
        addCard(Zone.HAND, playerA, "Ensign Pavel Chekov");
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.LIBRARY, playerB, "Lightning Bolt");
        addCard(Zone.LIBRARY, playerB, "Forest"); // top

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Ensign Pavel Chekov");
        setChoice(playerA, "Forest");
        playLand(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Forest");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Forest", 1);
        assertExileCount(playerB, "Lightning Bolt", 1);
    }
}
