package org.mage.test.cards.single.trc;

import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class ShoreLeavePlanetTest extends CardTestPlayerBase {

    @Test
    public void test_BecomesCopyUntilEndOfTurn() {
        addCard(Zone.BATTLEFIELD, playerA, "Shore Leave Planet");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{4}{U}: This land becomes a copy", "Hill Giant");
        checkPermanentCount("two giants", 1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Hill Giant", 2);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertPermanentCount(playerA, "Shore Leave Planet", 1);
    }

    @Test
    public void test_CopyIsARobotArtifact() {
        addCard(Zone.BATTLEFIELD, playerA, "Shore Leave Planet");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);
        addCard(Zone.BATTLEFIELD, playerA, "Ornithopter");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{4}{U}: This land becomes a copy", "Ornithopter");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Ornithopter", 2);
    }
}
