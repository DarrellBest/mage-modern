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
 * DARRELLBEST-FORK: guards ActionEvaluator's wiring into the priority prompt --
 * mirrors KannaCounterAnnotationAITest's pattern (which guards the same wiring point
 * for counters) one class over.
 * <p>
 * Live evidence this fixes: Kanna sacrificed her entire board to Altar of Dementia
 * (Sacrifice a creature: target player mills cards equal to the sacrificed creature's
 * power) six times in one real game, including a 4/4 Ao the Dawn Sky, because the
 * shortlist showed only the ability's oracle text -- no evaluation of what the
 * sacrifice itself cost her. It is legal at every priority window she holds a creature
 * (no mana cost), so nothing about affordability ever stopped her from choosing it.
 * <p>
 * This does not assert on how Kanna plays -- like KannaCounterAnnotationAITest, it only
 * captures the exact prompt text ComputerPlayerKanna.priority() builds and checks the
 * missing state (the real sacrifice cost) is now in it. The scripted OllamaClient
 * returns null (no tool call) after capturing each prompt, driving the decision to
 * heuristic fallback without needing to predict any catalog id ahead of time.
 */
public class KannaActionEvaluatorAnnotationAITest extends CardTestPlayerBaseAI {

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
    public void test_Kanna_RendersTheRealSacrificeCostOnAltarOfDementia() {
        // A zero-mana sacrifice ability, legal the instant a creature is in play --
        // exactly the shape that let the live bug fire every priority window.
        addCard(Zone.BATTLEFIELD, playerA, "Altar of Dementia", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 1); // 2/2, Kanna's only creature

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        boolean sawSacrificeCost = false;
        for (String prompt : capturedPrompts) {
            if (prompt.contains("sacrifices a creature") && prompt.contains("Grizzly Bears 2/2")
                    && prompt.contains("1 -> 0")) {
                sawSacrificeCost = true;
                break;
            }
        }
        assertTrue("prompt must state the real sacrifice cost (which creature is at stake and the "
                        + "resulting board count), not just the ability's oracle text -- captured prompts: "
                        + capturedPrompts,
                sawSacrificeCost);
    }
}
