package org.mage.test.kanna;

import mage.player.ai.kanna.TurnPlan;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TurnPlanTest {

    @Test
    public void ofAcceptsEachOfTheFiveValidGoals() {
        assertNotNull(TurnPlan.of("RACE", "push damage", 3));
        assertNotNull(TurnPlan.of("STABILIZE", "regain footing", 3));
        assertNotNull(TurnPlan.of("DEVELOP", "build board", 3));
        assertNotNull(TurnPlan.of("CONTROL", "answer threats", 3));
        assertNotNull(TurnPlan.of("MILL", "library nearly empty", 3));
    }

    @Test
    public void ofRejectsAnyGoalOutsideTheFixedSet() {
        assertNull("free-text goal must be rejected", TurnPlan.of("WIN_NOW", "go fast", 3));
        assertNull("lower-case must be rejected -- the set is exact, not case-insensitive",
                TurnPlan.of("race", "push damage", 3));
        assertNull("null goal must be rejected", TurnPlan.of(null, "anything", 3));
        assertNull("empty goal must be rejected", TurnPlan.of("", "anything", 3));
    }

    @Test
    public void defaultPlanIsAlwaysDevelop() {
        TurnPlan plan = TurnPlan.defaultPlan(7);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(7, plan.turnNumber);
        assertNotNull(plan.rationale);
        assertTrue("default plan must still carry a real, non-empty rationale",
                !plan.rationale.isEmpty());
    }

    @Test
    public void renderIsOneCompactLineCarryingGoalAndRationale() {
        TurnPlan plan = TurnPlan.of("MILL", "their library is down to single digits", 5);
        String rendered = plan.render();
        assertTrue("render must not contain a newline -- it is re-sent with every decision "
                        + "prompt this turn and must stay a single compact line",
                rendered.indexOf('\n') < 0 && rendered.indexOf('\r') < 0);
        assertTrue("rendered line must name the goal", rendered.contains("MILL"));
        assertTrue("rendered line must carry the rationale",
                rendered.contains("their library is down to single digits"));
        assertTrue("rendered line must be short -- one goal word plus one short sentence, "
                        + "not a paragraph", rendered.length() < 200);
    }

    @Test
    public void renderOnDefaultPlanIsAlsoCompactAndSingleLine() {
        String rendered = TurnPlan.defaultPlan(1).render();
        assertTrue(rendered.contains("DEVELOP"));
        assertTrue(rendered.indexOf('\n') < 0 && rendered.indexOf('\r') < 0);
    }

    @Test
    public void goalWithSurroundingWhitespaceIsTrimmedButStillValidated() {
        // a model occasionally pads its own output with whitespace -- goal itself is not
        // trimmed before validation (an untrimmed goal must fail VALID_GOALS.contains, as
        // strict as any other malformed answer), but the rationale is, so a real answer
        // is not rejected purely for cosmetic whitespace.
        TurnPlan plan = TurnPlan.of("DEVELOP", "  build up mana.  ", 2);
        assertNotNull(plan);
        assertEquals("build up mana.", plan.rationale);
    }
}
