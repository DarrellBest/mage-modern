package org.mage.test.cards.single.trk;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

public class Species8472Test extends CardTestPlayerBase {

    private static final String SPECIES = "Species 8472";

    @Test
    public void test_GainLifePerDifferentlyNamedLand() {
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 4);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 2);
        addCard(Zone.HAND, playerA, SPECIES);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, SPECIES);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerA, 20 + 2); // Forest and Island
    }

    @Test
    public void test_CantBeBlockedByArtifactCreatures() {
        addCard(Zone.BATTLEFIELD, playerA, SPECIES);
        addCard(Zone.BATTLEFIELD, playerB, "Ornithopter");

        attack(1, playerA, SPECIES);
        block(1, playerB, "Ornithopter", SPECIES);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 7);
    }

    @Test
    public void test_OpponentsArtifactAbilityCantTargetIt() {
        addCard(Zone.BATTLEFIELD, playerA, SPECIES);
        addCard(Zone.BATTLEFIELD, playerB, "Rod of Ruin"); // {3}, {T}: 1 damage to any target
        addCard(Zone.BATTLEFIELD, playerB, "Wastes", 3);

        activateAbility(2, PhaseStep.PRECOMBAT_MAIN, playerB, "{3}, {T}: ", SPECIES);

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        try {
            execute();
            Assert.fail("an opponent's artifact ability must not be able to target " + SPECIES);
        } catch (AssertionError e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("Can't find ability to activate command"));
        }
    }

    @Test
    public void test_ControlSameAbilityCanTargetOtherCreatures() {
        addCard(Zone.BATTLEFIELD, playerA, SPECIES);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears");
        addCard(Zone.BATTLEFIELD, playerB, "Rod of Ruin");
        addCard(Zone.BATTLEFIELD, playerB, "Wastes", 3);

        activateAbility(2, PhaseStep.PRECOMBAT_MAIN, playerB, "{3}, {T}: ", "Grizzly Bears");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        assertDamageReceived(playerA, "Grizzly Bears", 1);
    }
}
