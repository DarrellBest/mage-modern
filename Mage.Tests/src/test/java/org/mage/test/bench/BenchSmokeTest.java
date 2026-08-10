package org.mage.test.bench;

import mage.bench.BenchConfig;
import mage.bench.BenchGame;
import mage.bench.GameResult;
import mage.bench.Termination;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: smoke test for the Mage.Bench harness. Uses stock
 * ComputerPlayer on both seats so it needs no Ollama and runs in CI.
 * Exercises the harness, not the LLM path.
 */
public class BenchSmokeTest {

    private BenchConfig config() {
        return BenchConfig.parse(new String[]{
                "--games=3",
                "--seed=4242",
                "--playerA=base",
                "--playerB=base",
                "--turnCap=15",
                "--deckDir=."
        });
    }

    @Test
    public void threeGamesAllTerminate() {
        BenchConfig config = config();
        for (int i = 0; i < config.games; i++) {
            GameResult result = BenchGame.run(config, i, config.baseSeed + i, i % 2 == 1);
            assertNotNull("game " + i + " produced no result", result);
            assertNotNull("game " + i + " has no termination", result.termination);
            assertTrue("game " + i + " failed: " + result.errorMessage,
                    result.termination != Termination.ERROR);
            assertTrue("game " + i + " played no turns", result.turns > 0);
        }
    }

    @Test
    public void sameSeedProducesSameOutcome() {
        BenchConfig config = config();
        GameResult first = BenchGame.run(config, 0, 777L, false);
        GameResult second = BenchGame.run(config, 0, 777L, false);
        assertEquals(first.termination, second.termination);
        assertEquals(first.winner, second.winner);
        assertEquals(first.turns, second.turns);
    }
}
