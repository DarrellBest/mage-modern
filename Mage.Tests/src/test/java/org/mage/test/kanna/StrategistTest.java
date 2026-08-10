package org.mage.test.kanna;

import com.google.gson.JsonArray;
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

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static ToolCall commitPlan(String goal, String rationale, String[] conditionals, String[] prohibitions) {
        JsonObject args = new JsonObject();
        args.addProperty("goal", goal);
        args.addProperty("rationale", rationale);
        args.add("conditionals", stringArray(conditionals));
        args.add("prohibitions", stringArray(prohibitions));
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
        Strategist strategist = new Strategist(scripted(commitPlan("STABILIZE", "behind on board",
                new String[]{"If they sweep, hold back."}, new String[]{"Do NOT overextend."})));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals("STABILIZE", plan.goal);
        assertEquals("behind on board", plan.rationale);
        assertEquals(4, plan.turnNumber);
        assertEquals(1, plan.conditionals.size());
        assertEquals("If they sweep, hold back.", plan.conditionals.get(0));
        assertEquals(1, plan.prohibitions.size());
        assertEquals("Do NOT overextend.", plan.prohibitions.get(0));
        assertEquals(0, strategist.getInvalidCount());
    }

    @Test
    public void missingConditionalsAndProhibitionsFieldsYieldEmptyListsNotInvalid() {
        // absent arrays are a legitimate answer (a turn with nothing to pre-commit to) --
        // see stringAndArrayFieldSchema's comment on why they are not required fields.
        JsonObject args = new JsonObject();
        args.addProperty("goal", "DEVELOP");
        args.addProperty("rationale", "steady");
        Strategist strategist = new Strategist(scripted(new ToolCall("commit_plan", args)));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals("DEVELOP", plan.goal);
        assertTrue(plan.conditionals.isEmpty());
        assertTrue(plan.prohibitions.isEmpty());
        assertEquals(0, strategist.getInvalidCount());
    }

    @Test
    public void overLimitConditionalsAreTrimmedAndNotCountedAsInvalid() {
        Strategist strategist = new Strategist(scripted(commitPlan("DEVELOP", "steady",
                new String[]{"If A.", "If B.", "If C.", "If D."}, new String[]{})));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals(3, plan.conditionals.size());
        assertEquals("too many conditionals is a model being over-eager, not a genuine error",
                0, strategist.getInvalidCount());
    }

    @Test
    public void overLimitProhibitionsAreTrimmedAndNotCountedAsInvalid() {
        Strategist strategist = new Strategist(scripted(commitPlan("DEVELOP", "steady",
                new String[]{}, new String[]{"Do NOT A.", "Do NOT B.", "Do NOT C."})));
        TurnPlan plan = strategist.plan("prompt", 4);
        assertEquals(2, plan.prohibitions.size());
        assertEquals(0, strategist.getInvalidCount());
    }

    @Test
    public void invalidGoalFallsBackToDefaultPlanAndIsCounted() {
        Strategist strategist = new Strategist(scripted(commitPlan("WIN_NOW", "go fast",
                new String[]{}, new String[]{})));
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

    /**
     * Malformed shape: conditionals sent back as a bare string instead of an array. Must not
     * throw out of plan() -- it must degrade to the default plan exactly like any other
     * genuinely bad answer, and count toward invalidCount the same way.
     */
    @Test
    public void malformedConditionalsShapeFallsBackToDefaultPlanAndIsCounted() {
        JsonObject args = new JsonObject();
        args.addProperty("goal", "RACE");
        args.addProperty("rationale", "closing fast");
        args.addProperty("conditionals", "If they block, trade."); // wrong shape: string, not array
        Strategist strategist = new Strategist(scripted(new ToolCall("commit_plan", args)));
        TurnPlan plan = strategist.plan("prompt", 5);
        assertEquals("DEVELOP", plan.goal);
        assertEquals(5, plan.turnNumber);
        assertEquals(1, strategist.getInvalidCount());
    }

    @Test
    public void promptIsSentThroughUnmodified() {
        final StringBuilder captured = new StringBuilder();
        OllamaClient client = new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                captured.append(prompt);
                return commitPlan("DEVELOP", "steady", new String[]{}, new String[]{});
            }
        };
        new Strategist(client).plan("the exact planning prompt", 1);
        assertTrue(captured.toString().equals("the exact planning prompt"));
    }
}
