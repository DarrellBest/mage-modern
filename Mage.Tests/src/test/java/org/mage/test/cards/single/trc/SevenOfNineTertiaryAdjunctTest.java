package org.mage.test.cards.single.trc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class SevenOfNineTertiaryAdjunctTest extends CardTestPlayerBase {

    private static final String SEVEN = "Seven of Nine, Tertiary Adjunct";

    @Test
    public void test_DiscardNonlandChargesAnArtifact() {
        addCard(Zone.BATTLEFIELD, playerA, SEVEN);
        addCard(Zone.BATTLEFIELD, playerA, "Isolinear Processor");
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "<i>That Is Irrelevant</i>");
        setChoice(playerA, "Lightning Bolt");       // discard
        addTarget(playerA, "Isolinear Processor");  // reflexive trigger

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, "Lightning Bolt", 1);
        assertCounterCount(playerA, "Isolinear Processor", CounterType.CHARGE, 2);
    }
}
