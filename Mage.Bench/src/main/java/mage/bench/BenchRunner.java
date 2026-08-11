package mage.bench;

import mage.cards.repository.CardScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for a benchmark run.
 * <p>
 * Games run serially on the main thread, for two independent reasons: Ollama
 * serializes requests so parallelism buys little, and RandomUtil holds one
 * process-wide static Random, so parallel in-process games would destroy
 * per-game reproducibility. Running on the main thread also satisfies
 * ThreadUtils.ensureRunInGameThread(), which allowlists the thread named
 * "main".
 *
 * @author Darrell Best
 */
public final class BenchRunner {

    private static final int PROGRESS_EVERY = 5;

    public static void main(String[] args) throws Exception {
        BenchConfig config = BenchConfig.parse(args);

        if (config.usesLlm()) {
            OllamaPreflight.check(config.ollamaUrl, config.model);
            System.out.println("Ollama preflight OK: " + config.model + " at " + config.ollamaUrl);
        }

        System.out.println(String.format("Running %d games: %s vs %s (seed %d, turn cap %d)",
                config.games, config.playerA, config.playerB, config.baseSeed, config.turnCap));

        // off by default: only built, and only registered per game by BenchGame, when
        // --trackCards was actually given -- see BenchConfig.trackCards and BenchGame.run's
        // 5-arg overload for the zero-cost-when-absent contract this depends on
        CardPlayTracker cardTracker = null;
        if (config.trackCards != null) {
            // deck loading needs the card DB scanned first; BenchGame.run does this too but not
            // until the first game runs, and this needs it earlier to record deck lists up front
            CardScanner.scan();
            cardTracker = new CardPlayTracker();
            cardTracker.recordDeck(config.deckA, BenchGame.loadDeckList(config.deckDir, config.deckA));
            cardTracker.recordDeck(config.deckB, BenchGame.loadDeckList(config.deckDir, config.deckB));
        }

        List<GameResult> results = new ArrayList<>();
        try (ResultWriter writer = new ResultWriter(config.out)) {
            for (int i = 0; i < config.games; i++) {
                long seed = config.baseSeed + i;
                boolean seatSwapped = (i % 2 == 1);

                GameResult result = BenchGame.run(config, i, seed, seatSwapped, cardTracker);
                writer.append(result);
                results.add(result);

                System.out.println(String.format("  game %d/%d  seed=%d  %s  winner=%s  turns=%d  %dms",
                        i + 1, config.games, seed, result.termination,
                        result.winner == null ? "-" : result.winner,
                        result.turns, result.wallTimeMs));

                if ((i + 1) % PROGRESS_EVERY == 0 && (i + 1) < config.games) {
                    System.out.print(SummaryReporter.format(
                            SummaryReporter.summarize(results, config.playerA),
                            config.playerA, config.playerB));
                }
            }
        }

        System.out.print(SummaryReporter.format(
                SummaryReporter.summarize(results, config.playerA),
                config.playerA, config.playerB));
        System.out.println("Results written to " + config.out);

        if (cardTracker != null) {
            cardTracker.writeReport(config.trackCards);
            System.out.println("Card-play report written to " + config.trackCards);
        }
    }
}
