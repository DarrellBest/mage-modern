package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class DrLeonardBonesMcCoyTest extends CardTestPlayerBase {

    private static final String BONES = "Dr. Leonard \"Bones\" McCoy";

    @Test
    public void test_ModeOneRegrowth() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.HAND, playerA, BONES);
        addCard(Zone.GRAVEYARD, playerA, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, BONES);
        setModeChoice(playerA, "1");
        addTarget(playerA, "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertHandCount(playerA, "Hill Giant", 1);
    }

    @Test
    public void test_ModeTwoReturnsWithCounter() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain");
        addCard(Zone.HAND, playerA, BONES);
        addCard(Zone.HAND, playerA, "Lightning Bolt");
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, BONES);
        setModeChoice(playerA, "2");
        addTarget(playerA, "Hill Giant");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Lightning Bolt", "Hill Giant");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.UPKEEP);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertCounterCount(playerA, "Hill Giant", CounterType.P1P1, 1);
    }
}
