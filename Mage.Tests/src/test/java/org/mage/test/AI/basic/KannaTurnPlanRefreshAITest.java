package org.mage.test.AI.basic;

import com.google.gson.JsonObject;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.Strategist;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: proves ComputerPlayerKanna's wiring calls Strategist at most once
 * per Kanna turn, no matter how many real decisions (priority/attack/block) that turn
 * contains -- the "one extra call per turn, not per decision" constraint. A version of
 * this wiring that refreshed on every decision instead of on turn change would turn a
 * ~15s-per-decision player into something several times slower; this is the regression
 * guard for that.
 * <p>
 * Distinguishes a planning call from every other kind of model call by inspecting the
 * FIRST tool offered (Strategist always offers exactly one tool, "commit_plan";
 * KannaAgent's chooseAction/choosePairs always offer "choose_action"/"declare_attackers"/
 * "declare_blockers" first) -- see PlanCountingClient below. Every non-planning call
 * returns null (no tool call at all), which KannaAgent/Strategist both treat as an
 * immediate, single-call fallback to heuristics (no internal retry -- that retry lives in
 * the real OllamaClient.call(), bypassed here by overriding call() directly, the same
 * pattern KannaAgentTest's ScriptedClient and KannaFallbackAITest already use). The
 * heuristic fallback then plays the land and casts the creature on its own, exactly as
 * KannaFallbackAITest already proves for an unreachable Ollama host -- this test's board
 * is deliberately built the same way (1 land in hand, 1 more land drop, 1 cheap creature)
 * so that path is exercised without needing to script every individual decision.
 */
public class KannaTurnPlanRefreshAITest extends CardTestPlayerBaseAI {

    /** Counts total model calls and, separately, how many were the planning call. */
    private static class PlanCountingClient extends OllamaClient {
        int totalCalls = 0;
        int planCalls = 0;

        PlanCountingClient() {
            super("http://unused", "unused");
        }

        @Override
        public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
            totalCalls++;
            if (!tools.isEmpty() && Strategist.TOOL_COMMIT_PLAN.equals(functionName(tools.get(0)))) {
                planCalls++;
                JsonObject args = new JsonObject();
                args.addProperty("goal", "DEVELOP");
                args.addProperty("rationale", "test plan, steady development");
                return new ToolCall(Strategist.TOOL_COMMIT_PLAN, args);
            }
            // every other decision (priority/attack/block) deliberately returns no tool
            // call at all, forcing a single-call fallback to heuristics rather than
            // scripting each individual decision by hand -- see class javadoc.
            return null;
        }

        private static String functionName(JsonObject tool) {
            JsonObject function = tool.getAsJsonObject("function");
            return function != null && function.has("name") ? function.get("name").getAsString() : null;
        }
    }

    private final PlanCountingClient countingClient = new PlanCountingClient();

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            TestComputerPlayerKanna kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            kannaPlayer.setScriptedOllamaClient(countingClient);
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_RefreshesThePlanOnceEachOwnTurn_NotPerDecision() {
        // enough for two land drops and two creature casts, one pair per Kanna turn, so
        // both turn 1 and turn 3 (turn 2 is PlayerB's -- see the turn-order note below)
        // contain multiple real decisions for the fallback heuristics to make.
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 1);
        addCard(Zone.HAND, playerA, "Forest", 2);
        addCard(Zone.HAND, playerA, "Grizzly Bears", 2);

        setStrictChooseMode(true);
        // PlayerA goes first (createPlayer order in CardTestPlayerBaseAI), so turn 1 and
        // turn 3 are Kanna's own turns and turn 2 is PlayerB's -- stopping after turn 3
        // exercises the turn-change refresh twice.
        setStopAt(3, PhaseStep.END_TURN);
        execute();

        assertEquals("Strategist must be consulted exactly once per Kanna turn (turns 1 and 3), "
                        + "never per individual decision", 2, countingClient.planCalls);
        assertTrue("this scenario must also produce ordinary (non-planning) decisions this run, "
                        + "or the planCalls count above would be trivially satisfied by a player "
                        + "that never decides anything else",
                countingClient.totalCalls > countingClient.planCalls);
    }
}
