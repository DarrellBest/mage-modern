package org.mage.test.kanna;

import mage.player.ai.kanna.ActionEvaluator;
import mage.player.ai.kanna.CreatureView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-function tests for ActionEvaluator's cost arithmetic -- no Game, no Ability,
 * hand-constructed plain values only, same split CombatEvaluatorTest uses against
 * CombatEvaluator.
 * <p>
 * DARRELLBEST-FORK: guards the Altar of Dementia class of bug -- Kanna sacrificed her
 * entire board (including a 4/4) to a zero-mana ability five times in one game because
 * nothing ever computed what the sacrifice cost her. These tests are on the arithmetic
 * that fix relies on, independent of any live game.
 */
public class ActionEvaluatorTest {

    private CreatureView creature(String name, int power, int toughness) {
        return new CreatureView("id", name, power, toughness,
                false, false, false, false, false, false, false, false);
    }

    // ---- sacrificeAmongText: engine picks which permanent later ----

    @Test
    public void sacrificeAmongOneCreatureNamesIt() {
        String text = ActionEvaluator.sacrificeAmongText(
                Arrays.asList(creature("Saproling", 1, 1)));
        assertTrue(text.contains("Saproling 1/1"));
        assertTrue("must state the resulting board count", text.contains("1 -> 0"));
    }

    @Test
    public void sacrificeAmongSeveralCreaturesReportsSmallestAndLargest() {
        List<CreatureView> board = Arrays.asList(
                creature("Saproling", 1, 1),
                creature("Hill Giant", 3, 3),
                creature("Ao the Dawn Sky", 4, 4));
        String text = ActionEvaluator.sacrificeAmongText(board);
        assertTrue("must name the smallest creature", text.contains("smallest Saproling 1/1"));
        assertTrue("must name the largest creature", text.contains("largest Ao the Dawn Sky 4/4"));
        assertTrue("must state control count", text.contains("you control 3"));
        assertTrue("must state the resulting board count", text.contains("3 -> 2"));
    }

    @Test
    public void sacrificeAmongZeroCreaturesDoesNotClaimABoardChange() {
        String text = ActionEvaluator.sacrificeAmongText(new ArrayList<CreatureView>());
        assertTrue(text.contains("you control none"));
        // no creature exists to name or to subtract from a board count -- must not
        // invent a "0 -> -1" style claim
        assertTrue(!text.contains("->"));
    }

    // ---- sacrificeExactText: source/attached-to is already known ----

    @Test
    public void sacrificeExactNamesTheKnownCreatureAndBoardCount() {
        String text = ActionEvaluator.sacrificeExactText(creature("Nightmare", 5, 5),
                Arrays.asList(creature("Nightmare", 5, 5), creature("Saproling", 1, 1)));
        assertTrue(text.contains("Nightmare 5/5"));
        assertTrue(text.contains("2 -> 1"));
    }

    @Test
    public void sacrificeExactNeverGoesNegativeWhenItIsYourOnlyCreature() {
        String text = ActionEvaluator.sacrificeExactText(creature("Nightmare", 5, 5),
                Arrays.asList(creature("Nightmare", 5, 5)));
        assertTrue(text.contains("1 -> 0"));
    }

    // ---- payLifeText ----

    @Test
    public void payLifeReportsBeforeAndAfter() {
        assertEquals("pays 3 life: 13 -> 10", ActionEvaluator.payLifeText(13, 3));
    }

    @Test
    public void payLifeTakingYouToExactlyZero() {
        assertEquals("pays 3 life: 3 -> 0", ActionEvaluator.payLifeText(3, 3));
    }

    @Test
    public void payingZeroLifeIsNotAReportedCost() {
        // MTG rule 118.4: paying 0 life is not considered paying any life
        assertEquals("", ActionEvaluator.payLifeText(20, 0));
    }

    // ---- discardText ----

    @Test
    public void discardOneCardReportsHandBeforeAndAfter() {
        assertEquals("discards a card: hand 5 -> 4", ActionEvaluator.discardText(5, 1));
    }

    @Test
    public void discardSeveralCardsReportsTheCount() {
        assertEquals("discards 2 cards: hand 5 -> 3", ActionEvaluator.discardText(5, 2));
    }

    @Test
    public void discardFromAnEmptyHandReportsNothing() {
        // nothing is actually given up -- must not claim a discard happened
        assertEquals("", ActionEvaluator.discardText(0, 0));
    }

    @Test
    public void discardNeverGoesNegativeWhenAskedForMoreThanTheHandHolds() {
        String text = ActionEvaluator.discardText(2, 5);
        assertTrue(text.contains("hand 2 -> 0"));
    }
}
