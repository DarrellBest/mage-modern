package org.mage.test.bench;

import mage.bench.BenchConfig;
import mage.bench.BenchGame;
import mage.bench.GameResult;
import mage.bench.Termination;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DARRELLBEST-FORK: smoke test for Commander support in the Mage.Bench
 * harness ({@code --gameType=commander}). Uses stock ComputerPlayer on both
 * seats so it needs no Ollama and runs fast in CI, and real 100-card
 * Commander decks (commander card in the deck's sideboard, as XMage stores
 * it) so this also exercises the commander-from-sideboard placement path
 * in {@code GameCommanderImpl.init()} -- not just the harness plumbing
 * {@link BenchSmokeTest} already covers for the two-player path.
 */
public class BenchCommanderSmokeTest {

    private BenchConfig config() {
        return BenchConfig.parse(new String[]{
                "--games=1",
                "--seed=4242",
                "--playerA=base",
                "--playerB=base",
                "--turnCap=10",
                "--deckDir=.",
                "--deckA=CommanderDuel.dck",
                "--deckB=CommanderDuel_UW.dck",
                "--gameType=commander"
        });
    }

    @Test
    public void commanderGameStartsAndTerminates() {
        BenchConfig config = config();
        GameResult result = BenchGame.run(config, 0, config.baseSeed, false);
        assertNotNull("game produced no result", result);
        assertNotNull("game has no termination", result.termination);
        assertTrue("game failed: " + result.errorMessage,
                result.termination != Termination.ERROR);
        assertTrue("game played no turns", result.turns > 0);
    }
}
