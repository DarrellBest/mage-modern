package org.mage.test.cards.single.trk;

import mage.abilities.keyword.IndestructibleAbility;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DeathIsIrrelevantTest extends CardTestPlayerBase {

    // Artifact creatures you control have indestructible.
    // At the beginning of your end step, assimilate target creature card from a graveyard.
    @Test
    public void test_EndStepAssimilateAndIndestructible() {
        addCard(Zone.BATTLEFIELD, playerA, "Death Is Irrelevant");
        addCard(Zone.GRAVEYARD, playerB, "Grizzly Bears");

        addTarget(playerA, "Grizzly Bears"); // end step trigger

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Grizzly Bears", 1);
        assertSubtype("Grizzly Bears", SubType.BORG);
        // it's an artifact creature now, so it has indestructible
        assertAbility(playerA, "Grizzly Bears", IndestructibleAbility.getInstance(), true);
    }
}
