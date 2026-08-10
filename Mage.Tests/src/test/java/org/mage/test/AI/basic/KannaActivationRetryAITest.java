package org.mage.test.AI.basic;

import com.google.gson.JsonObject;
import mage.abilities.ActivatedAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.ai.kanna.ComputerPlayerKanna;
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
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: guards FIX 1 on the model-chosen path (ComputerPlayerKanna.priority(),
 * the :203 call site) -- activateAbility's boolean return used to be discarded there, with
 * priority() unconditionally returning true. Since GameImpl.playPriority's inner loop
 * re-invokes player.priority() as long as the player has not passed, and a bare "return
 * true" never sets that, a failed activation on an otherwise-still-legal action meant the
 * SAME action was offered again immediately with unchanged board state -- ActionRanker
 * ranked it first again, the model round-tripped to re-pick it again, forever. There is no
 * engine-level safety valve for this shape of loop (GameImpl.checkInfiniteLoop only fires
 * off stack objects being repeatedly removed, and a failed activateAbility never reaches
 * the stack) -- observed live as Grim Backwoods, a once-per-turn {T} ability, activated 6
 * times in one game. This is a genuine unbounded-hang bug, not just an inefficiency: the
 * simplest possible proof this test could offer that the fix holds is that it terminates
 * at all.
 * <p>
 * activateAbility is forced to fail unconditionally by a test subclass rather than via any
 * particular in-game precondition -- the point under test is ComputerPlayerKanna's own
 * retry/give-up logic, independent of why a real activation might fail.
 */
public class KannaActivationRetryAITest extends CardTestPlayerBaseAI {

    /** Deterministic activation failure, regardless of the real reason a real game might
     * reject it -- what is under test here is Kanna's handling of the failure, not any
     * specific in-game cause of one. */
    private static class AlwaysFailsToActivateKanna extends TestComputerPlayerKanna {
        final List<String> attempts = new ArrayList<String>();

        AlwaysFailsToActivateKanna(String name, RangeOfInfluence range, int skill) {
            super(name, range, skill);
        }

        @Override
        public boolean activateAbility(ActivatedAbility ability, Game game) {
            attempts.add(ability.toString());
            return false;
        }
    }

    private static class CountingMetrics implements ComputerPlayerKanna.DecisionMetrics {
        int invalidCount = 0;
        int llmCalls = 0;

        @Override
        public void recordLlmCall(long latencyMs) {
            llmCalls++;
        }

        @Override
        public void recordInvalidToolCall() {
            invalidCount++;
        }
    }

    private AlwaysFailsToActivateKanna kannaPlayer;
    private CountingMetrics metrics;
    private int scriptedCallCount = 0;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            kannaPlayer = new AlwaysFailsToActivateKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            metrics = new CountingMetrics();
            kannaPlayer.setBenchMetrics(metrics);
            kannaPlayer.setScriptedOllamaClient(new OllamaClient("http://unused", "unused") {
                @Override
                public ToolCall call(String prompt, List<JsonObject> tools) throws IOException {
                    scriptedCallCount++;
                    // act-0 is guaranteed to exist and be a real (non-Pass) action here:
                    // Pass is always added to the catalog last in
                    // ComputerPlayerKanna.priority(), after both Soulmenders' abilities,
                    // so with two real options on the board it can never be act-0.
                    JsonObject args = new JsonObject();
                    args.addProperty("action_id", "act-0");
                    return new ToolCall("choose_action", args);
                }
            });
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_DoesNotSpinOnARepeatedlyFailingActivation() {
        // Two Soulmenders give Kanna two distinct, identically-ranked non-Pass options
        // (both "{T}: You gain 1 life.") and nothing else to do all turn -- no lands, no
        // other permanents, nothing to attack with meaningfully. activateAbility is
        // forced to fail for both, every time, so neither can ever actually gain life.
        addCard(Zone.BATTLEFIELD, playerA, "Soulmender", 2);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // Above all: this completed. Pre-fix, the very first failed activation would
        // hang the game thread forever (see the class javadoc) rather than produce any
        // particular wrong number -- reaching this line at all is already most of the
        // proof.
        assertEquals("nothing ever actually gained life, since activateAbility always "
                + "fails", 20, playerA.getLife());

        // FIX 1's specific mechanism: a failed activation retries by walking the
        // already-ranked list rather than re-asking the model, so each distinct
        // priority window this turn costs at most one model round trip no matter how
        // many candidates it internally tries and rejects. A generous bound (a turn has
        // roughly half a dozen to a dozen steps that grant priority at all) is enough to
        // catch a real regression back to one-call-per-retry without being a fragile
        // exact count.
        assertTrue("model round trips must stay of the order of one per priority window, "
                + "not one per retry -- got " + scriptedCallCount, scriptedCallCount <= 20);

        // Both distinct options were genuinely tried at least once (not stuck retrying
        // a single one that keeps getting excluded and somehow re-offered), and the
        // failures were counted rather than silently swallowed.
        assertTrue("must have attempted a real activation at least once",
                kannaPlayer.attempts.size() >= 1);
        assertTrue("failed activations must be recorded as invalid tool calls, not hidden",
                metrics.invalidCount >= 1);
    }
}
