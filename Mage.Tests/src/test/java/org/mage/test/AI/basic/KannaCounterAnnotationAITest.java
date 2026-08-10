package org.mage.test.AI.basic;

import com.google.gson.JsonObject;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: guards FIX 4 (T8 finding #1) -- Kanna activated Jar of Eyeballs
 * ({3}, {T}, Remove all eyeball counters from Jar of Eyeballs: look at the top X cards,
 * X = counters removed) five times in one real game with zero eyeball counters every
 * time, burning {3} for X=0, because the priority prompt rendered the ability's oracle
 * text but never the permanent's actual counter count -- the model had no way to know
 * X was going to be 0.
 * <p>
 * This does not assert on how Kanna plays -- it only captures the exact prompt text
 * ComputerPlayerKanna.priority() builds and checks the missing state (0 eyeball
 * counters) is now in it. The scripted OllamaClient returns null (no tool call) after
 * capturing each prompt, which is a real, already-handled response shape (see
 * KannaAgentTest's noToolCallAtAllFallsBackAndIsCounted) -- it drives the decision to
 * heuristic fallback without needing to know any catalog id ahead of time, which this
 * test has no way to predict.
 */
public class KannaCounterAnnotationAITest extends CardTestPlayerBaseAI {

    private final List<String> capturedPrompts = new ArrayList<String>();

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            TestComputerPlayerKanna kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
                @Override
                public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                    capturedPrompts.add(prompt);
                    return null;
                }
            });
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_RendersZeroEyeballCountersOnJarOfEyeballs() {
        // Never triggered (no creature of PlayerA's has died), so Jar of Eyeballs
        // genuinely has zero eyeball counters -- exactly the state that was invisible
        // to the model before this fix. {3} generic to pay its activation cost.
        addCard(Zone.BATTLEFIELD, playerA, "Jar of Eyeballs", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Forest", 3);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        boolean sawIt = false;
        for (String prompt : capturedPrompts) {
            if (prompt.contains("0 eyeball counters")) {
                sawIt = true;
                break;
            }
        }
        assertTrue("prompt must state the permanent's actual (zero) eyeball counter count, "
                + "not just its oracle text -- captured prompts: " + capturedPrompts, sawIt);
    }
}
