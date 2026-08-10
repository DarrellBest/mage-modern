package org.mage.test.kanna;

import mage.player.ai.kanna.TurnPlan;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TurnPlanTest {

    private static List<String> list(String... entries) {
        return Arrays.asList(entries);
    }

    @Test
    public void ofAcceptsEachOfTheFiveValidGoals() {
        assertNotNull(TurnPlan.of("RACE", "push damage", list(), list(), 3));
        assertNotNull(TurnPlan.of("STABILIZE", "regain footing", list(), list(), 3));
        assertNotNull(TurnPlan.of("DEVELOP", "build board", list(), list(), 3));
        assertNotNull(TurnPlan.of("CONTROL", "answer threats", list(), list(), 3));
        assertNotNull(TurnPlan.of("MILL", "library nearly empty", list(), list(), 3));
    }

    @Test
    public void ofRejectsAnyGoalOutsideTheFixedSet() {
        assertNull("free-text goal must be rejected", TurnPlan.of("WIN_NOW", "go fast", list(), list(), 3));
        assertNull("lower-case must be rejected -- the set is exact, not case-insensitive",
                TurnPlan.of("race", "push damage", list(), list(), 3));
        assertNull("null goal must be rejected", TurnPlan.of(null, "anything", list(), list(), 3));
        assertNull("empty goal must be rejected", TurnPlan.of("", "anything", list(), list(), 3));
    }

    @Test
    public void defaultPlanIsAlwaysDevelopWithNoConditionalsOrProhibitions() {
        TurnPlan plan = TurnPlan.defaultPlan(7);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(7, plan.turnNumber);
        assertNotNull(plan.rationale);
        assertTrue("default plan must still carry a real, non-empty rationale",
                !plan.rationale.isEmpty());
        assertTrue("a default plan reflects no real planning call -- it must not invent "
                + "contingencies", plan.conditionals.isEmpty());
        assertTrue("a default plan reflects no real planning call -- it must not invent "
                + "prohibitions", plan.prohibitions.isEmpty());
    }

    @Test
    public void renderCarriesGoalRationaleConditionalsAndProhibitions() {
        TurnPlan plan = TurnPlan.of("RACE", "they are at 12, I can close in three turns",
                list("If they block Craw Wurm with something smaller, take the trade."),
                list("Do NOT sacrifice creatures to the Altar this turn."), 5);
        String rendered = plan.render();
        assertTrue("rendered plan must name the goal", rendered.contains("RACE"));
        assertTrue("rendered plan must carry the rationale",
                rendered.contains("they are at 12, I can close in three turns"));
        assertTrue("rendered plan must carry the conditional",
                rendered.contains("If they block Craw Wurm with something smaller, take the trade."));
        assertTrue("rendered plan must carry the prohibition",
                rendered.contains("Do NOT sacrifice creatures to the Altar this turn."));
    }

    @Test
    public void renderOnDefaultPlanNamesTheGoal() {
        String rendered = TurnPlan.defaultPlan(1).render();
        assertTrue(rendered.contains("DEVELOP"));
    }

    @Test
    public void goalWithSurroundingWhitespaceIsTrimmedButStillValidated() {
        // a model occasionally pads its own output with whitespace -- goal itself is not
        // trimmed before validation (an untrimmed goal must fail VALID_GOALS.contains, as
        // strict as any other malformed answer), but the rationale is, so a real answer
        // is not rejected purely for cosmetic whitespace.
        TurnPlan plan = TurnPlan.of("DEVELOP", "  build up mana.  ", list(), list(), 2);
        assertNotNull(plan);
        assertEquals("build up mana.", plan.rationale);
    }

    @Test
    public void overLimitConditionalsAreTrimmedToTheCapNotRejected() {
        List<String> fourConditionals = list(
                "If they block, trade.",
                "If they sweep, hold back.",
                "If they cast removal, bait with the smaller creature.",
                "If they pass with mana up, play around a trick.");
        TurnPlan plan = TurnPlan.of("DEVELOP", "steady", fourConditionals, list(), 4);
        assertNotNull("too many conditionals must be trimmed, not treated as an invalid plan", plan);
        assertEquals(3, plan.conditionals.size());
        assertEquals("If they block, trade.", plan.conditionals.get(0));
        assertEquals("If they sweep, hold back.", plan.conditionals.get(1));
        assertEquals("If they cast removal, bait with the smaller creature.", plan.conditionals.get(2));
    }

    @Test
    public void overLimitProhibitionsAreTrimmedToTheCapNotRejected() {
        List<String> threeProhibitions = list(
                "Do NOT sacrifice creatures to the Altar.",
                "Do NOT attack into an open sweeper.",
                "Do NOT tap out on their turn.");
        TurnPlan plan = TurnPlan.of("STABILIZE", "behind on board", list(), threeProhibitions, 4);
        assertNotNull(plan);
        assertEquals(2, plan.prohibitions.size());
        assertEquals("Do NOT sacrifice creatures to the Altar.", plan.prohibitions.get(0));
        assertEquals("Do NOT attack into an open sweeper.", plan.prohibitions.get(1));
    }

    @Test
    public void blankAndNullEntriesAreDroppedRatherThanCountingTowardTheCap() {
        List<String> withBlanks = new ArrayList<String>();
        withBlanks.add("  ");
        withBlanks.add(null);
        withBlanks.add("If they block, trade.");
        TurnPlan plan = TurnPlan.of("DEVELOP", "steady", withBlanks, list(), 2);
        assertNotNull(plan);
        assertEquals(1, plan.conditionals.size());
        assertEquals("If they block, trade.", plan.conditionals.get(0));
    }

    /**
     * Regression test for a real bug found by running the live Altar-of-Dementia behavioural
     * test: with conditionals rendered before prohibitions, a genuine model-produced
     * prohibition ("Do NOT sacrifice creatures unnecessarily.") was sliced off the tail by the
     * 400-char cap because two conditionals came first and ate the whole budget -- so the
     * decision prompt for the very Altar-of-Dementia activation this feature exists to stop
     * never saw the prohibition at all. Prohibitions must survive a tight budget; conditionals
     * are what gives way.
     */
    @Test
    public void renderTruncatesConditionalsBeforeDroppingAnyProhibition() {
        List<String> conditionals = list(
                "If the opponent blocks with a creature that can kill my 6/4, I will trade down to eliminate the threat.",
                "If the opponent plays a board wipe, I will focus on surviving and drawing cards next turn.");
        List<String> prohibitions = list(
                "Do NOT tap creatures for mana if it prevents attacking.",
                "Do NOT sacrifice creatures unnecessarily.");
        TurnPlan plan = TurnPlan.of("RACE",
                "With a board of three creatures including a 6/4 threat and no cards in hand, "
                        + "applying maximum pressure is the best path to victory.",
                conditionals, prohibitions, 1);
        String rendered = plan.render();

        assertTrue("both prohibitions must survive truncation even though the combined "
                        + "goal/rationale/conditionals/prohibitions text exceeds the cap",
                rendered.contains("Do NOT tap creatures for mana if it prevents attacking.")
                        && rendered.contains("Do NOT sacrifice creatures unnecessarily."));
        assertTrue("this scenario must actually exceed the cap pre-truncation, or the test "
                        + "would not be exercising truncation at all",
                rendered.length() == 400);
    }

    @Test
    public void renderIsHardCappedAndTruncatedDeterministically() {
        StringBuilder longRationale = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longRationale.append("very long rationale text ");
        }
        List<String> longConditionals = list(
                "If the opponent does absolutely anything at all on their turn then react accordingly and adjust everything.",
                "If a sweeper resolves then hold back every creature that survives and rebuild from nothing at all.",
                "If they attack with everything then decide block by block whether trading is worthwhile this turn.");
        List<String> longProhibitions = list(
                "Do NOT sacrifice any creature to any free outlet for any reason this entire turn no matter what.",
                "Do NOT tap out for anything unless it is completely and totally necessary to survive this turn.");
        TurnPlan plan = TurnPlan.of("STABILIZE", longRationale.toString(), longConditionals, longProhibitions, 9);
        assertNotNull(plan);
        String rendered = plan.render();
        assertTrue("render must never exceed the hard character cap regardless of how "
                        + "verbose the model's fields were",
                rendered.length() <= 400);

        // truncation must be deterministic: rendering the same plan twice yields the same string.
        assertEquals(rendered, plan.render());
    }

    @Test
    public void renderStaysUnderCapForAnOrdinaryPlan() {
        TurnPlan plan = TurnPlan.of("RACE", "they are at 12, I can close in three turns",
                list("If they block Craw Wurm with something smaller, take the trade.",
                        "If they cast a sweeper, hold the second Kobold back."),
                list("Do NOT sacrifice creatures to the Altar this turn."), 10);
        assertFalse("an ordinary, well-behaved plan should not need truncation at all",
                plan.render().length() > 400);
    }
}
