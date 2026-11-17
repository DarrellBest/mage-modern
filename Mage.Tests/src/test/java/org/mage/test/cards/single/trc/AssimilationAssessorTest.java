package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class AssimilationAssessorTest extends CardTestPlayerBase {

    // When this creature enters, exile target opponent's graveyard.
    // Whenever this creature attacks, assimilate a creature card exiled with it.
    private static final String AA = "Assimilation Assessor";

    @Test
    public void test_ExileGraveyardThenAssimilateOnAttack() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 5);
        addCard(Zone.HAND, playerA, AA);
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears");
        addCard(Zone.GRAVEYARD, playerB, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, AA);
        addTarget(playerA, playerB);
        checkExileCount("graveyard exiled", 1, PhaseStep.POSTCOMBAT_MAIN, playerB, "Grizzly Bears", 1);

        attack(3, playerA, AA);
        setChoice(playerA, "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertSubtype("Grizzly Bears", SubType.BORG);
        assertExileCount(playerB, "Lightning Bolt", 1);
        assertLife(playerB, 20 - 5);
    }
}
