package org.mage.test.AI.basic;

import mage.abilities.ActivatedAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.player.ai.kanna.ComputerPlayerKanna;
import org.junit.Test;
import org.mage.test.player.TestComputerPlayerKanna;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.CardTestPlayerBaseAI;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: FIX 1's other call site -- the heuristic-fallback path in
 * ComputerPlayerKanna.priority() (the :193 line in the bug report, reached when
 * decision.fallback is true rather than when the model committed to an id). Same defect,
 * same fix (see KannaActivationRetryAITest's javadoc for the full mechanism): the return
 * of activateAbility(chosen, game) was discarded there too, with priority() unconditionally
 * returning true regardless of whether the heuristic's top-ranked pick actually activated.
 * <p>
 * Ollama unreachable (KannaFallbackAITest's own approach) forces every decision through
 * KannaAgent's transport-exception branch straight to Decision.fallback(), so this
 * exercises the OTHER half of activateWithFallback's two call sites without needing a
 * scripted client at all.
 */
public class KannaActivationRetryFallbackAITest extends CardTestPlayerBaseAI {

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

        @Override
        public void recordLlmCall(long latencyMs) {
        }

        @Override
        public void recordInvalidToolCall() {
            invalidCount++;
        }
    }

    private AlwaysFailsToActivateKanna kannaPlayer;
    private CountingMetrics metrics;

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if ("PlayerA".equals(name)) {
            kannaPlayer = new AlwaysFailsToActivateKanna(name, RangeOfInfluence.ONE, getSkillLevel());
            metrics = new CountingMetrics();
            kannaPlayer.setBenchMetrics(metrics);
            // nothing listens on this port -- every Ollama call fails fast, forcing
            // every decision through Decision.fallback() (KannaFallbackAITest's pattern)
            kannaPlayer.setOllamaUrl("http://127.0.0.1:1");
            TestPlayer testPlayer = new TestPlayer(kannaPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    @Test
    public void test_Kanna_HeuristicFallbackDoesNotSpinOnARepeatedlyFailingActivation() {
        // A land in hand and nothing else: FIX 5 made the ranker score an unrecognised
        // activated ability BELOW Pass, so a Soulmender-style board (as used in
        // KannaActivationRetryAITest's model-path sibling) would have the fallback
        // choose Pass outright here without ever reaching activateAbility -- which is
        // correct for that case, but does not exercise this call site. A land play
        // scores above Pass (ActionRanker.SCORE_LAND), so the heuristic fallback's
        // top-ranked pick is genuinely the land, forcing a real activateAbility call
        // this method can make fail.
        addCard(Zone.HAND, playerA, "Forest", 1);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // As with the model-chosen path: pre-fix, the first failed activation here
        // would hang the game thread forever rather than produce a wrong number --
        // reaching this line at all is most of the proof.
        assertPermanentCount(playerA, "Forest", 0);
        assertTrue("must have attempted a real activation at least once",
                kannaPlayer.attempts.size() >= 1);
        assertTrue("failed activations must be recorded as invalid tool calls, not hidden",
                metrics.invalidCount >= 1);
    }
}
