package org.mage.test.AI.basic;

import com.google.gson.JsonObject;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.player.ai.kanna.ComputerPlayerKanna;
import mage.player.ai.kanna.KannaAgent;
import mage.player.ai.kanna.OllamaClient;
import mage.player.ai.kanna.ToolCall;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: guards the get_card_text fix (see KannaAgent.InspectionAnswerer's
 * javadoc and ComputerPlayerKanna.describeActionCardText/describeTargetCardText).
 * Before this fix:
 * - the priority-path answerer returned catalog.labelFor(id) for get_card_text --
 *   exactly the shortlist line the model already had -- so asking for detail never
 *   surfaced anything new (live evidence: 3 of 12 decisions in one real game hit the
 *   4-call cap without ever committing, all traced to this).
 * - the targeting-path answerer did not handle get_card_text at all and returned null,
 *   which KannaAgent.chooseAction() reads as "unknown tool": an immediate
 *   Decision.fallback() plus an invalidCount++ for a tool KannaAgent itself advertised
 *   to the model, corrupting BenchMetrics' model-quality metric.
 * <p>
 * Both scenarios are exercised end to end (real game state, real
 * ComputerPlayerKanna.priority()/chooseTarget()) with a scripted OllamaClient standing
 * in for the network call, following KannaCounterAnnotationAITest's capture-the-prompt
 * pattern -- the interesting assertion is on the text KannaAgent fed back to the model
 * as "Result of get_card_text: ...", not on how the game ultimately played out.
 */
public class KannaGetCardTextAITest extends CardTestPlayerBaseAI {

    private final List<String> capturedPrompts = new ArrayList<String>();
    private int invalidToolCalls = 0;
    private TestComputerPlayerKanna kannaPlayer;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            kannaPlayer.setBenchMetrics(new ComputerPlayerKanna.DecisionMetrics() {
                @Override
                public void recordLlmCall(long latencyMs) {
                }

                @Override
                public void recordInvalidToolCall() {
                    invalidToolCalls++;
                }
            });
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_GetCardTextInPriorityPathReturnsRealOracleTextNotTheShortlistLabel() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);

        kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
            private int callCount = 0;

            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                capturedPrompts.add(prompt);
                callCount++;
                if (callCount == 1) {
                    // inspect the only real action (Lightning Bolt) before deciding
                    JsonObject args = new JsonObject();
                    args.addProperty("id", "act-0");
                    return new ToolCall(KannaAgent.TOOL_GET_CARD_TEXT, args);
                }
                // whatever happens next (including any target-selection round trip),
                // decline to commit -- we only care what the first answer put in this
                // prompt, captured above
                return null;
            }
        });

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertTrue("expected at least the inspection call and its follow-up",
                capturedPrompts.size() >= 2);
        String followUp = capturedPrompts.get(1);
        assertTrue("must contain Lightning Bolt's real oracle text (its damage effect), "
                        + "not just the shortlist label 'Cast Lightning Bolt' -- got: " + followUp,
                followUp.contains("damage"));
        assertFalse("must not just echo the shortlist label back",
                followUp.contains("Result of get_card_text: Cast Lightning Bolt"));
    }

    @Test
    public void test_Kanna_GetCardTextInTargetingPathIsAnsweredNotTreatedAsUnknown() {
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.HAND, playerA, "Lightning Bolt", 1);
        // a creature target alongside both players means Lightning Bolt's "any target"
        // has more than one legal candidate, so target selection is a real model
        // decision (chooseOneTargetAgentically) rather than the single-candidate
        // auto-pick that needs no round trip at all
        addCard(Zone.BATTLEFIELD, playerB, "Serra Angel", 1);

        kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
            private int callCount = 0;

            @Override
            public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                capturedPrompts.add(prompt);
                callCount++;
                JsonObject args = new JsonObject();
                if (callCount == 1) {
                    // priority(): commit straight to casting Lightning Bolt
                    args.addProperty("action_id", "act-0");
                    return new ToolCall("choose_action", args);
                }
                if (callCount == 2) {
                    // chooseTarget(): inspect the first candidate before committing
                    args.addProperty("id", "act-0");
                    return new ToolCall(KannaAgent.TOOL_GET_CARD_TEXT, args);
                }
                // commit to whichever candidate we just inspected
                args.addProperty("action_id", "act-0");
                return new ToolCall("choose_action", args);
            }
        });

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertEquals("get_card_text in the targeting path must never be treated as an "
                        + "unknown tool -- it is advertised right alongside show_all_actions",
                0, invalidToolCalls);

        boolean sawInspectionAnswer = false;
        for (String prompt : capturedPrompts) {
            if (prompt.contains("Result of " + KannaAgent.TOOL_GET_CARD_TEXT + ": ")
                    && !prompt.contains("Result of " + KannaAgent.TOOL_GET_CARD_TEXT + ": No such id")) {
                sawInspectionAnswer = true;
                break;
            }
        }
        assertTrue("expected a real (non-null, non-error) answer to get_card_text in the "
                + "targeting path -- captured prompts: " + capturedPrompts, sawInspectionAnswer);
    }
}
