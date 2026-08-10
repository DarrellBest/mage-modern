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
 * DARRELLBEST-FORK: guards FIX 6, the getMaxBlockedBy() sibling of the existing
 * getMinBlockedBy() check in declareBlocksAgentically -- see KannaAgenticBlockAITest's
 * javadoc for the min-side version of this same defect shape. Unlike the min case there
 * is no "no legal configuration existed" escape hatch for a too-large group in
 * CombatGroup.checkBlockRestrictions (an over-max group is unconditionally illegal), so a
 * model assigning too many blockers to a Challenger Troll-style attacker would drive
 * Combat.selectBlockers's retry loop up to 20 times, each retry re-firing
 * DECLARE_BLOCKERS_STEP_PRE and re-invoking the LLM. Challenger Troll gives itself
 * getMaxBlockedBy() == 1 (power >= 4) with no extra setup, so this is a real,
 * off-the-shelf card rather than a hand-constructed effect.
 * <p>
 * Same scripted-OllamaClient seam as KannaAgenticBlockAITest, exercising the real
 * (non-fallback) response path: the model proposes both Wall of Stone as blockers on
 * Challenger Troll, which is individually legal per Permanent.canBlock() for each pair
 * but collectively exceeds the max of 1.
 */
public class KannaAgenticBlockMaxAITest extends CardTestPlayerBaseAI {

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            JsonObject pair1 = new JsonObject();
            pair1.addProperty("blocker_id", "blk-0");
            pair1.addProperty("attacker_id", "atk-0");
            JsonObject pair2 = new JsonObject();
            pair2.addProperty("blocker_id", "blk-1");
            pair2.addProperty("attacker_id", "atk-0");
            JsonArray blocks = new JsonArray();
            blocks.add(pair1);
            blocks.add(pair2);
            JsonObject args = new JsonObject();
            args.add("blocks", blocks);
            final ToolCall oversizedBlock = new ToolCall("declare_blockers", args);

            TestComputerPlayerKanna kannaPlayer = new TestComputerPlayerKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
                @Override
                public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                    return oversizedBlock;
                }
            });
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_TrimsOversizedMaxBlockedByGroupFromModelResponse() {
        addCard(Zone.BATTLEFIELD, playerA, "Wall of Stone", 2); // 0/8 x2
        addCard(Zone.BATTLEFIELD, playerB, "Challenger Troll", 1); // 6/5, gives itself maxBlockedBy 1
        addCard(Zone.BATTLEFIELD, playerB, "Forest", 5);

        attack(2, playerB, "Challenger Troll");

        setStrictChooseMode(true);
        setStopAt(2, PhaseStep.END_TURN);
        execute();

        // Pre-fix, both walls being declared is unconditionally illegal here (unlike
        // the min-blocked-by case, there is no "impossible configuration" escape
        // hatch), which drives the engine's retry storm and, in test mode, an
        // exception -- so completing at all is already most of the proof. The
        // trim-not-drop behaviour is the rest: a legal 1-blocker assignment still
        // happened (0/8 easily survives 6 damage, 0 power deals nothing back), so no
        // damage got through, rather than the whole group being discarded and Kanna
        // taking the full 6.
        assertLife(playerA, 20);
    }
}
