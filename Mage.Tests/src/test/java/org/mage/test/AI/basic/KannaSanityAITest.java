package org.mage.test.AI.basic;

import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

/**
 * DARRELLBEST-FORK: standalone sanity check for ComputerPlayerKanna -- does it
 * take the most basic actions (play a land, cast a creature) at all, without
 * needing a live playtest to find out.
 */
public class KannaSanityAITest extends CardTestPlayerBaseAI {

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            TestPlayer testPlayer = new TestPlayer(new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel()));
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_PlaysLandAndCastsCreature() {
        // nothing tricky here: 1 land already in play, 1 more in hand, 1 cheap creature in hand.
        // if Kanna is truly doing nothing, none of this happens by end of turn 1.
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Grizzly Bears", 1); // {1}{G}, 2/2 vanilla

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Forest", 2);
        assertPermanentCount(playerA, "Grizzly Bears", 1);
    }

    @Test
    public void test_Kanna_AttacksWithNothingToLose() {
        // opponent has zero blockers and Kanna has a creature that's safe to attack with --
        // if attack declaration is broken, life totals won't move at all
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 20 - 2);
    }
}
