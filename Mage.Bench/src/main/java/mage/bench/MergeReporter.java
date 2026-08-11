package mage.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Merges the JSONL output of several parallel {@link BenchRunner} worker processes into one
 * pooled report. Entry point for {@code tools/bench-parallel.sh}; also usable by hand to
 * re-report an old set of part files.
 * <p>
 * The pooling is deliberately done over the merged RAW results -- every game from every worker
 * concatenated into a single list handed to {@link SummaryReporter#summarize} -- and never by
 * combining per-worker summaries. Averaging per-worker win rates would weight a worker that
 * finished 3 games the same as one that finished 200, which is wrong whenever workers complete
 * different counts, and they routinely do: game length varies by an order of magnitude
 * (10s-149s measured), so equal-sized worker shards do not finish in equal time, and a worker
 * that dies partway contributes a short shard. Pooling the raw games makes worker boundaries
 * invisible to the statistics, which is the only way the Wilson interval means what it says.
 * <p>
 * All statistics come from {@link SummaryReporter} unchanged -- this class computes no win rate
 * and no confidence interval of its own. The one number it adds is the per-GAME wall-time
 * distribution: {@code RunSummary}'s p50/p95 are per-TURN times (wallTimeMs/turns), which answer
 * "how fast does the AI think" but not "how long does a game take", and it is the latter that
 * sizes a sweep and picks a worker count. It uses {@link SummaryReporter#percentile} for that
 * rather than a second percentile definition.
 *
 * @author Darrell Best
 */
public final class MergeReporter {

    private MergeReporter() {
    }

    public static void main(String[] args) throws Exception {
        String playerA = "playerA";
        String playerB = "playerB";
        int expected = 0;
        String merged = null;
        List<String> parts = new ArrayList<>();

        for (String arg : args) {
            if (!arg.startsWith("--")) {
                parts.add(arg);
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Bad argument '" + arg + "', expected --name=value");
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if ("playerA".equals(key)) {
                playerA = value;
            } else if ("playerB".equals(key)) {
                playerB = value;
            } else if ("expected".equals(key)) {
                expected = Integer.parseInt(value);
            } else if ("merged".equals(key)) {
                merged = value;
            } else {
                throw new IllegalArgumentException("Unknown argument '--" + key + "'");
            }
        }

        if (parts.isEmpty()) {
            throw new IllegalArgumentException("No part files given. Usage: MergeReporter "
                    + "[--playerA=k] [--playerB=k] [--expected=n] [--merged=path] part1.jsonl part2.jsonl ...");
        }

        List<GameResult> all = new ArrayList<>();
        StringBuilder partLines = new StringBuilder();
        for (String part : parts) {
            Path path = Paths.get(part);
            // ResultWriter.read returns an empty list for a file that does not exist, which is the
            // right behaviour for a run in progress but exactly the wrong one here: a worker whose
            // output file never appeared is a dead worker, and silently pooling it as "0 games"
            // is the under-reporting this tool exists to prevent.
            if (!Files.exists(path)) {
                throw new IOException("Part file missing (worker never wrote it?): " + path.toAbsolutePath());
            }
            List<GameResult> partResults;
            try {
                partResults = ResultWriter.read(part);
            } catch (RuntimeException e) {
                // Gson throws on a truncated final line, which is what a worker killed mid-append
                // leaves behind. Name the file: "JsonSyntaxException" alone identifies nothing.
                throw new IOException("Part file is corrupt (worker killed mid-write?): "
                        + path.toAbsolutePath() + " -- " + e, e);
            }
            all.addAll(partResults);
            partLines.append(String.format("  %-40s %5d games%n", path.getFileName(), partResults.size()));
        }

        if (merged != null) {
            Path mergedPath = Paths.get(merged);
            // ResultWriter opens for APPEND (by design -- a long run must survive a crash), so
            // writing into an existing file would silently fold a previous run's games into this
            // run's pooled statistics. Refuse instead.
            if (Files.exists(mergedPath)) {
                throw new IOException("Merged output already exists, refusing to append to it: "
                        + mergedPath.toAbsolutePath());
            }
            try (ResultWriter writer = new ResultWriter(merged)) {
                for (GameResult result : all) {
                    writer.append(result);
                }
            }
        }

        System.out.println();
        System.out.println("=== Merged parts ===");
        System.out.print(partLines);
        System.out.println(String.format("  %-40s %5d games", "TOTAL", all.size()));
        if (merged != null) {
            System.out.println("Merged results written to " + Paths.get(merged).toAbsolutePath());
        }

        System.out.print(SummaryReporter.format(SummaryReporter.summarize(all, playerA), playerA, playerB));

        List<Long> gameWallMs = new ArrayList<>(all.size());
        for (GameResult result : all) {
            gameWallMs.add(result.wallTimeMs);
        }
        Collections.sort(gameWallMs);
        System.out.println(String.format("Game time:    p50 %d ms, p95 %d ms  (per game, wall clock)",
                SummaryReporter.percentile(gameWallMs, 0.50),
                SummaryReporter.percentile(gameWallMs, 0.95)));

        int errors = 0;
        int timeouts = 0;
        for (GameResult result : all) {
            if (result.termination == Termination.ERROR) {
                errors++;
            } else if (result.termination == Termination.TIMEOUT) {
                timeouts++;
            }
        }
        if (errors > 0) {
            System.out.println();
            System.out.println("WARNING: " + errors + " game(s) terminated with ERROR and are excluded from the "
                    + "win-rate denominator. Check the worker logs for stack traces.");
        }
        if (timeouts > 0) {
            System.out.println();
            System.out.println("WARNING: " + timeouts + " of " + all.size() + " game(s) hit the --maxGameSeconds "
                    + "budget and are excluded from the win-rate denominator. A large share here means the "
                    + "budget is deciding the sample: raise it, or accept that slow matchups are unmeasured.");
        }

        if (expected > 0 && all.size() < expected) {
            System.out.println();
            System.out.println("ERROR: pooled " + all.size() + " games but " + expected
                    + " were requested -- " + (expected - all.size()) + " missing. The interval above is "
                    + "over a SMALLER sample than you asked for; do not report it as an " + expected + "-game run.");
            System.exit(1);
        }
    }
}
