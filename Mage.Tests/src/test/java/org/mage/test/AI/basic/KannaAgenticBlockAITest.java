package org.mage.test.AI.basic;

import com.google.gson.JsonArray;
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
import java.util.List;

/**
 * DARRELLBEST-FORK: exercises the AGENTIC block path (declareBlocksAgentically), which
 * KannaFallbackAITest deliberately does not. Pointing Kanna at an unreachable Ollama URL
 * (KannaFallbackAITest's approach) forces every decision through
 * KannaAgent.choosePairs's transport-exception branch straight to
 * Decision.fallback() -- so declareBlocksAgentically returns before ever reaching its
 * own pair-grouping code and heuristicBlocks runs instead. An early draft of this
 * coverage was named for this exact defect but built on KannaFallbackAITest's
 * unreachable-Ollama setup, so it never actually executed the code it claimed to guard;
 * caught before commit and rebuilt here instead.
 * <p>
 * To exercise the real (non-fallback) response path without a real network call,
 * Kanna's OllamaClient is replaced with a scripted one that returns a canned
 * declare_blockers tool call directly, via TestComputerPlayerKanna.setScriptedOllamaClient
 * (new test-harness hook, backed by ComputerPlayerKanna.newAgent() being widened from
 * private to protected specifically as this test seam). This is the same
 * OllamaClient-subclass pattern KannaAgentTest already uses one layer down (its
 * ScriptedClient, and the anonymous subclass in transportFailureFallsBackRatherThanPropagating)
 * -- applied here so the real, private grouping logic inside declareBlocksAgentically
 * runs against a real game/Permanent/menace-attacker state instead of being tested in
 * isolation from fabricated data.
 * <p>
 * Guards a pre-existing bug sharing Important 2's root cause, but on the model-response
 * path instead of the heuristic one: declareBlocksAgentically committed the model's
 * validated pairs gated only on Permanent.canBlock(), which does not check a
 * minimum-blockers restriction (menace's Permanent.minBlockedBy). If the model assigns a
 * single blocker to a menace attacker, that reaches CombatGroup.checkBlockRestrictions
 * ungated, which rejects it, and Combat.selectBlockers re-invokes selectBlockers up to 20
 * times -- each retry re-firing DECLARE_BLOCKERS_STEP_PRE and, on this path, re-invoking
 * the LLM -- before throwing in test mode. Task 8 is a recorded real game with Ollama
 * reachable: precisely the configuration that executes this path, making this the
 * higher-probability version of the bug heuristicBlocks already had fixed for it.
 */
public class KannaAgenticBlockAITest extends CardTestPlayerBaseAI {

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            // "blk-0"/"atk-0" are the deterministic short ids declareBlocksAgentically
            // assigns in this exact scenario: atk-0 because Boggart Brute is the only
            // attacker, blk-0 because it is the first of two functionally identical
            // Wall of Stone permanents found by getAvailableBlockers() (which one is
            // irrelevant -- same card, same stats, either is "the first"). This
            // response assigns only that one blocker to the menace attacker:
            // individually legal per Permanent.canBlock(), but short of Boggart
            // Brute's getMinBlockedBy() == 2.
            JsonObject pair = new JsonObject();
            pair.addProperty("blocker_id", "blk-0");
            pair.addProperty("attacker_id", "atk-0");
            JsonArray blocks = new JsonArray();
            blocks.add(pair);
            JsonObject args = new JsonObject();
            args.add("blocks", blocks);
            final ToolCall undersizedMenaceBlock = new ToolCall("declare_blockers", args);

            TestComputerPlayerKanna kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
                @Override
                public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                    return undersizedMenaceBlock;
                }
            });
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_DropsUndersizedMenaceGroupFromModelResponse() {
        // Same battlefield shape as KannaFallbackAITest's menace tests (two Wall of
        // Stone, so nothing else offers Kanna a decision this game -- no lands, no
        // spells, no other priority-window choice for the scripted client to be asked
        // about incorrectly), but here the model DOES answer -- with the illegal
        // single-blocker assignment above -- rather than failing to reach Ollama at all.
        addCard(Zone.BATTLEFIELD, playerA, "Wall of Stone", 2); // 0/8 x2
        addCard(Zone.BATTLEFIELD, playerB, "Boggart Brute", 1); // 3/2, Menace
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 1);

        attack(2, playerB, "Boggart Brute");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // the model's undersized group was dropped outright -- neither wall blocks
        // ("either both or neither, never exactly one") -- so the full 3 gets through.
        // What actually matters here is what did NOT happen: no exception, no engine
        // retry storm, no repeated LLM calls (the scripted client always returns the
        // same single-blocker response, which KannaAgent.choosePairs only ever asks for
        // once per declareBlocksAgentically call -- a real retry storm would mean this
        // scripted client gets called far more than once, which it cannot meaningfully
        // answer differently, but the point is the engine must never ask). execute()
        // completing at all, with this exact life total, is the proof.
        assertLife(playerA, 20 - 3);
    }
}
