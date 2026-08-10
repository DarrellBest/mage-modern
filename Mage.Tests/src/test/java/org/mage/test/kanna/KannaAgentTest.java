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
import java.util.List;

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
            public String answer(ToolCall call) {
                if ("get_card_text".equals(call.name)) {
                    return "Mountain: taps for R.";
                }
                return null;
            }
        };
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
        assertTrue("must not exceed the cap", client.callCount <= 4);
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
}
