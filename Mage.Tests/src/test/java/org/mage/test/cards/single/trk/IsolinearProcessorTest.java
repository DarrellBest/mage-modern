package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class IsolinearProcessorTest extends CardTestPlayerBase {

    // {T}: Add {C}. Spend this mana only to cast an artifact spell or activate an ability of an artifact source.
    private static final String PROC = "Isolinear Processor";

    @Test
    public void test_PaysForArtifactSpell() {
        addCard(Zone.BATTLEFIELD, playerA, PROC);
        addCard(Zone.HAND, playerA, "Bottle Gnomes"); // {3}
        addCard(Zone.BATTLEFIELD, playerA, "Wastes", 2);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Bottle Gnomes");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Bottle Gnomes", 1);
        assertTapped(PROC, true);
    }

    @Test
    public void test_CantPayForNonartifactSpell() {
        addCard(Zone.BATTLEFIELD, playerA, PROC);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Grizzly Bears"); // {1}{G}

        checkPlayableAbility("can't use its mana", 1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cast Grizzly Bears", false);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();
    }
}
