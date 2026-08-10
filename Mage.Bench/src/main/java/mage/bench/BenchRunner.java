package mage.bench;

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

        List<GameResult> results = new ArrayList<>();
        try (ResultWriter writer = new ResultWriter(config.out)) {
            for (int i = 0; i < config.games; i++) {
                long seed = config.baseSeed + i;
                boolean seatSwapped = (i % 2 == 1);

                GameResult result = BenchGame.run(config, i, seed, seatSwapped);
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
    }
}
