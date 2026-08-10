package org.mage.test.kanna;

import com.google.gson.JsonObject;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.Strategist;
import mage.player.ai.kanna.ToolCall;
import mage.player.ai.kanna.TurnPlan;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Strategist's parse/validate path, driven by a scripted OllamaClient -- same pattern as
 * KannaAgentTest's ScriptedClient, one layer up (a single planning call rather than a
 * multi-round-trip decision loop).
 */
public class StrategistTest {

    private static ToolCall commitPlan(String goal, String rationale) {
        JsonObject args = new JsonObject();
        args.addProperty("goal", goal);
        args.addProperty("rationale", rationale);
        return new ToolCall("commit_plan", args);
    }

    private static OllamaClient scripted(final ToolCall response) {
        return new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                return response;
            }
        };
    }

    @Test
    public void validGoalIsAcceptedAndNotCountedAsInvalid() {
        Strategist strategist = new Strategist(scripted(commitPlan("STABILIZE", "behind on board")));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals("STABILIZE", plan.goal);
        assertEquals("behind on board", plan.rationale);
        assertEquals(4, plan.turnNumber);
        assertEquals(0, strategist.getInvalidCount());
    }

    @Test
    public void invalidGoalFallsBackToDefaultPlanAndIsCounted() {
        Strategist strategist = new Strategist(scripted(commitPlan("WIN_NOW", "go fast")));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(4, plan.turnNumber);
        assertEquals(1, strategist.getInvalidCount());
    }

    @Test
    public void wrongToolNameFallsBackToDefaultPlanAndIsCounted() {
        JsonObject args = new JsonObject();
        args.addProperty("goal", "RACE");
        Strategist strategist = new Strategist(scripted(new ToolCall("choose_action", args)));
        TurnPlan plan = strategist.plan("prompt", 2);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(1, strategist.getInvalidCount());
    }

    @Test
    public void noToolCallAtAllFallsBackToDefaultPlanAndIsCounted() {
        Strategist strategist = new Strategist(scripted(null));
        TurnPlan plan = strategist.plan("prompt", 9);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(9, plan.turnNumber);
        assertEquals(1, strategist.getInvalidCount());
    }

    /**
     * The behaviour the whole feature depends on failing safely: a failed planning call
     * (transport exception here, standing in for a timeout or an unreachable host) must
     * yield TurnPlan.defaultPlan(turn), never null and never an exception escaping to the
     * caller.
     */
    @Test
    public void transportFailureYieldsDefaultPlanRatherThanNoPlan() {
        OllamaClient exploding = new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                throw new IOException("connection refused");
            }
        };
        Strategist strategist = new Strategist(exploding);
        TurnPlan plan = strategist.plan("prompt", 6);
        TurnPlan expected = TurnPlan.defaultPlan(6);
        assertEquals(expected.goal, plan.goal);
        assertEquals(expected.turnNumber, plan.turnNumber);
        assertEquals(expected.rationale, plan.rationale);
        // transport failure is infrastructure, not the model answering badly -- must NOT
        // be counted the same way an invalid goal or missing tool call is (mirrors
        // KannaAgent.getInvalidCount()'s documented exclusion of the same path).
        assertEquals(0, strategist.getInvalidCount());
    }

    @Test
    public void missingGoalFieldFallsBackToDefaultPlanAndIsCounted() {
        JsonObject args = new JsonObject();
        args.addProperty("rationale", "no goal given");
        Strategist strategist = new Strategist(scripted(new ToolCall("commit_plan", args)));
        TurnPlan plan = strategist.plan("prompt", 3);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(1, strategist.getInvalidCount());
    }

    @Test
    public void toolCallWithNullArgumentsFallsBackToDefaultPlanRatherThanThrowing() {
        Strategist strategist = new Strategist(scripted(new ToolCall("commit_plan", null)));
        TurnPlan plan = strategist.plan("prompt", 1);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(1, strategist.getInvalidCount());
    }

    @Test
    public void promptIsSentThroughUnmodified() {
        final StringBuilder captured = new StringBuilder();
        OllamaClient client = new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                captured.append(prompt);
                return commitPlan("DEVELOP", "steady");
            }
        };
        new Strategist(client).plan("the exact planning prompt", 1);
        assertTrue(captured.toString().equals("the exact planning prompt"));
    }
}
