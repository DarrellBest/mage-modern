package org.mage.test.kanna;

import com.google.gson.JsonObject;
import mage.abilities.common.PassAbility;
import mage.player.ai.kanna.ActionCatalog;
import mage.player.ai.kanna.Decision;
import mage.player.ai.kanna.KannaAgent;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KannaAgentTest {

    /** Returns the queued responses in order, then null forever. */
    private static class ScriptedClient extends OllamaClient {
        private final List<ToolCall> script;
        private int index = 0;
        int callCount = 0;

        ScriptedClient(List<ToolCall> script) {
            super("http://unused", "unused");
            this.script = script;
        }

        @Override
        public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
            callCount++;
            if (index < script.size()) {
                return script.get(index++);
            }
            return null;
        }
    }

    private ToolCall commit(String actionId) {
        JsonObject args = new JsonObject();
        args.addProperty("action_id", actionId);
        return new ToolCall("choose_action", args);
    }

    private ToolCall inspect() {
        JsonObject args = new JsonObject();
        args.addProperty("id", "act-0");
        return new ToolCall("get_card_text", args);
    }

    private ActionCatalog catalog() {
        ActionCatalog catalog = new ActionCatalog();
        catalog.add(new PassAbility(), "Pass");
        catalog.add(new PassAbility(), "Play Mountain");
        return catalog;
    }

    private KannaAgent.InspectionAnswerer answerer() {
        return new KannaAgent.InspectionAnswerer() {
            @Override
            public Set<String> supportedTools() {
                return Collections.singleton(KannaAgent.TOOL_GET_CARD_TEXT);
            }

            @Override
            public String answer(ToolCall call) {
                if (KannaAgent.TOOL_GET_CARD_TEXT.equals(call.name)) {
                    return "Mountain: taps for R.";
                }
                return null;
            }
        };
    }

    /** Answerer that supports both known inspection tools, for the "always answered" tests. */
    private KannaAgent.InspectionAnswerer fullAnswerer() {
        return new KannaAgent.InspectionAnswerer() {
            @Override
            public Set<String> supportedTools() {
                return new HashSet<String>(Arrays.asList(
                        KannaAgent.TOOL_GET_CARD_TEXT, KannaAgent.TOOL_SHOW_ALL_ACTIONS));
            }

            @Override
            public String answer(ToolCall call) {
                if (KannaAgent.TOOL_GET_CARD_TEXT.equals(call.name)) {
                    return "Mountain: taps for R.";
                }
                if (KannaAgent.TOOL_SHOW_ALL_ACTIONS.equals(call.name)) {
                    return "act-0: Pass\nact-1: Play Mountain";
                }
                return null;
            }
        };
    }

    private ToolCall inspect(String toolName) {
        JsonObject args = new JsonObject();
        args.addProperty("id", "act-0");
        return new ToolCall(toolName, args);
    }

    @Test
    public void commitsImmediatelyWhenTheModelPicksAnAction() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(commit("act-1")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertEquals("act-1", decision.chosenId);
        assertFalse(decision.fallback);
        assertEquals(1, client.callCount);
    }

    @Test
    public void inspectionToolsAreAnsweredAndTheLoopContinues() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(inspect(), inspect(), commit("act-0")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertEquals("act-0", decision.chosenId);
        assertFalse(decision.fallback);
        assertEquals("two inspections then a commit", 3, client.callCount);
    }

    @Test
    public void capIsEnforcedAndFallsBack() {
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(inspect(), inspect(), inspect(), inspect(), inspect(), inspect()));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue("never committed within the cap", decision.fallback);
        assertEquals("cap must be used exactly", 4, client.callCount);
    }

    @Test
    public void unknownActionIdFallsBackAndIsCounted() {
        ScriptedClient client = new ScriptedClient(Arrays.asList(commit("act-999")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void noToolCallAtAllFallsBackAndIsCounted() {
        ScriptedClient client = new ScriptedClient(new ArrayList<ToolCall>());
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void transportFailureFallsBackRatherThanPropagating() {
        OllamaClient exploding = new OllamaClient("http://unused", "unused") {
            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                throw new IOException("connection refused");
            }
        };
        KannaAgent agent = new KannaAgent(exploding, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
    }

    @Test
    public void pairDecisionKeepsValidPairsAndDropsInvalidOnes() {
        JsonObject pair1 = new JsonObject();
        pair1.addProperty("attacker_id", "atk-0");
        pair1.addProperty("defender_id", "def-0");
        JsonObject pair2 = new JsonObject();
        pair2.addProperty("attacker_id", "atk-99");
        pair2.addProperty("defender_id", "def-0");
        com.google.gson.JsonArray attacks = new com.google.gson.JsonArray();
        attacks.add(pair1);
        attacks.add(pair2);
        JsonObject args = new JsonObject();
        args.add("attacks", attacks);

        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return "atk-0".equals(a);
                    }
                });
        assertFalse(decision.fallback);
        assertEquals(1, decision.pairs.size());
        assertEquals("atk-0", decision.pairs.get(0)[0]);
        assertEquals("one hallucinated pair dropped", 1, agent.getInvalidCount());
    }

    @Test
    public void emptyPairArrayIsAValidDecisionNotAFallback() {
        JsonObject args = new JsonObject();
        args.add("attacks", new com.google.gson.JsonArray());
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return true;
                    }
                });
        assertFalse("declining to attack is a real decision, not a failure", decision.fallback);
        assertTrue(decision.pairs.isEmpty());
    }

    @Test
    public void toolCallWithNullArgumentsFallsBackRatherThanThrowing() {
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", null)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return true;
                    }
                });
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void unknownToolNameFallsBackAndIsCounted() {
        // "summon_dragon" is not one of KannaAgent's known inspection tools at all --
        // this is a genuine model error (a hallucinated tool), and must keep falling
        // back and counting, exactly as before the advertise/answer coupling fix.
        JsonObject args = new JsonObject();
        args.addProperty("whatever", "x");
        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("summon_dragon", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void advertisedToolsAreNeverTreatedAsUnknown() {
        // Regression test for the targeting-path bug: get_card_text and
        // show_all_actions are both known KannaAgent inspection tools, and an answerer
        // that declares support for both (fullAnswerer(), mirroring how
        // ComputerPlayerKanna's real answerers now declare ALL_INSPECTION_TOOLS) must
        // have every one of them answered -- never treated as "unknown tool", which is
        // what happened to get_card_text in the targeting path before this fix (an
        // advertised-but-unanswered tool corrupted BenchMetrics' invalid-tool-call
        // count).
        ScriptedClient client = new ScriptedClient(Arrays.asList(
                inspect(KannaAgent.TOOL_GET_CARD_TEXT),
                inspect(KannaAgent.TOOL_SHOW_ALL_ACTIONS),
                commit("act-0")));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), fullAnswerer());
        assertFalse("both advertised tools must be answered, not treated as unknown", decision.fallback);
        assertEquals("act-0", decision.chosenId);
        assertEquals("neither advertised-tool call should count as a model error",
                0, agent.getInvalidCount());
    }

    @Test
    public void toolKnownToKannaAgentButNotDeclaredByThisAnswererStillFallsBackAndIsCounted() {
        // show_all_actions is a tool KannaAgent knows about globally, but answerer()
        // (used by this test) declares support for get_card_text only -- so it is not
        // advertised at this call site, and a model calling it anyway is exactly as
        // genuine a model error as calling a totally made-up tool name. This is what
        // keeps supportedTools() honest: declaring a subset must actually narrow what
        // counts as a valid call, not just what gets sent in the tools list.
        ScriptedClient client = new ScriptedClient(Arrays.asList(inspect(KannaAgent.TOOL_SHOW_ALL_ACTIONS)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.chooseAction("prompt", catalog(), answerer());
        assertTrue(decision.fallback);
        assertEquals(1, agent.getInvalidCount());
    }

    @Test
    public void nonObjectArrayElementIsDroppedAndCounted() {
        com.google.gson.JsonArray attacks = new com.google.gson.JsonArray();
        attacks.add("not-an-object");
        JsonObject valid = new JsonObject();
        valid.addProperty("attacker_id", "atk-0");
        valid.addProperty("defender_id", "def-0");
        attacks.add(valid);
        JsonObject args = new JsonObject();
        args.add("attacks", attacks);

        ScriptedClient client = new ScriptedClient(
                Arrays.asList(new ToolCall("declare_attackers", args)));
        KannaAgent agent = new KannaAgent(client, 4);
        Decision decision = agent.choosePairs("prompt", "declare_attackers", "attacks",
                "attacker_id", "defender_id", new KannaAgent.PairValidator() {
                    @Override
                    public boolean isValid(String a, String b) {
                        return true;
                    }
                });
        assertFalse("one bad element does not fail the whole decision", decision.fallback);
        assertEquals(1, decision.pairs.size());
        assertEquals(1, agent.getInvalidCount());
    }
}
