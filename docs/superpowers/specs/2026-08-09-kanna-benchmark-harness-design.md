# Kanna Benchmark Harness — Design

**Goal:** Build a headless, reproducible AI-vs-AI benchmark that reports Kanna's win rate against the stock XMage AI, plus the diagnostics needed to explain *why* a change helped or hurt. This is the instrument that makes all later Kanna strength work measurable.

**Constraint (hard):** Stays out of upstream directories. New isolated `Mage.Bench` module, the same principle already applied to the Kanna plugin, so pulling from `magefree/mage` never conflicts with it. The single exception is one small JUnit smoke test (`BenchSmokeTest`) in `Mage.Tests`.

---

## Context: why this is first

This is sub-project A of three. The larger goal is "the smartest and strongest possible AI." That decomposes into:

- **A. Benchmark harness** (this spec) — measurement.
- **B. Search quality** — `MCTSNode.simulate()` currently copies the game and calls `sim.resume()`, playing *every rollout to the end of the game*. Measured cost: ~3 rollouts/second, producing trees of 10–45 nodes on 17–34 simulations per decision. MCTS needs orders of magnitude more samples to beat heuristics, so the search is currently close to noise. Fix is heuristic evaluation with a depth cutoff, plus repairing tree reuse across Kanna's combat overrides.
- **C. Agentic loop + strategist** — LLM agent with tools (`get_board_state`, `get_legal_actions`, `evaluate_line` backed by MCTS), custom Modelfiles tuned for agentic reasoning.

A comes first because B and C are unfalsifiable without it, and because the chosen success metric — win rate vs stock AI — literally requires it to exist.

### Decisions carried in from brainstorming

| Question | Decision |
|---|---|
| Strength metric | Win rate vs stock AI, via headless harness |
| Speed target | **Relaxed.** Strength first; speed is a later optimization pass |
| LLM role | Agentic loop, with MCTS as a tool the agent calls |
| Model | Local models — `qwen3.6` preferred for speed if it sustains agentic tool use; `mistral-medium-3.5` as fallback. Custom Modelfiles for agentic reasoning |
| Harness shape | Hybrid: standalone runner + thin JUnit smoke test |

Speed being deprioritized is what makes the harness's throughput design (below) matter.

---

## Architecture

New module `Mage.Bench`, registered in the root `pom.xml` module list.

It plays **real games through the engine API** — `TwoPlayerDuel`, `DeckImporter`, real `ComputerPlayerKanna` / `ComputerPlayer7` / `ComputerPlayerMCTS` instances, `game.start()`. No `TestPlayer` wrapper, no `gameOptions.testMode`.

**Why not build on `CardTestPlayerBaseAI`:** the test framework wraps every player in `TestPlayer` and flips `isTestMode()`, which changes error handling and choice behavior. That wrapping is precisely what forced the `getRealPlayer()` fix in `ComputerPlayerMCTS.createMCTSGame()` — a benchmark built on it measures an artifact of the scaffolding rather than the shipped AI. `Mage.Tests` also has only `src/test`, making a `main()` awkward there.

### Throughput constraint (drives the whole design)

With the speed budget relaxed, an agentic loop per turn at 20–60s over ~20–40 turns is roughly **10–20 minutes per game**; 100 games is 17–33 hours serially.

Parallelism does not rescue this, for two independent reasons:

1. **Ollama serializes.** Concurrent games queue on one model instance, so throughput is LLM-bound, not CPU-bound.
2. **`RandomUtil` is a single process-wide static `Random`** (`Mage/src/main/java/mage/util/RandomUtil.java:14`). Seeding is global, so in-process parallel games trample each other's reproducibility.

Therefore: **serial and reproducible by default.** Optional *process*-level parallelism (one JVM per game, results merged from the output file) is reserved for LLM-free baseline runs — stock-vs-stock — where CPU genuinely is the bottleneck and reproducibility is per-process.

This is why incremental result writing is a core requirement rather than a nicety: runs are long enough that losing one to a crash at game 73 is a real cost.

---

## Components

All under `Mage.Bench/src/main/java/mage/bench/`.

### 1. `BenchConfig`
Immutable run parameters, built from CLI args with defaults.

| Field | Default | Notes |
|---|---|---|
| `games` | 20 | Number of games in the run |
| `baseSeed` | 12345 | Game *i* uses `baseSeed + i` |
| `deckA` / `deckB` | `RB Aggro.dck` | Resolved from a configurable deck directory |
| `playerA` / `playerB` | `kanna` / `cp7` | Keys for `PlayerFactory` |
| `skill` | 6 | Passed to AI constructors |
| `model` | `qwen3.6:latest` | Ollama model for Kanna |
| `turnCap` | 50 | Terminates stalled games |
| `out` | `bench-results.jsonl` | Append-only results file |

### 2. `PlayerFactory`
Maps a type string to a constructed `Player`: `kanna`, `cp7` (`ComputerPlayer7`), `mcts` (`ComputerPlayerMCTS`), `base` (`ComputerPlayer`). Any matchup becomes expressible without code changes, and Kanna-vs-stock and stock-vs-stock share one code path — which is what makes the stock-vs-stock baseline trustworthy as a control.

### 3. `BenchGame`
Runs exactly one game and returns a `GameResult`. Knows nothing about runs, files, or aggregation.

Sequence: seed `RandomUtil` → construct `TwoPlayerDuel` → load both decks via `DeckImporter` → build players via `PlayerFactory` → attach `BenchMetrics` if a player supports it → `game.start()` → collect result.

### 4. `GameResult`
`gameIndex`, `seed`, `winner` (player key, or none), `turns`, `wallTimeMs`, `perTurnMs[]`, `termination` (`WIN` / `CAP` / `ERROR`), `errorMessage`, `seatSwapped`, and an embedded `LlmStats` (`calls`, `totalLatencyMs`, `p50/p95LatencyMs`, `invalidToolCalls`).

### 5. `BenchMetrics`
The instrumentation sink Kanna reports into: LLM call count, per-call latency, and invalid-or-hallucinated tool-call count (Kanna already detects and logs these — see the `ignoring invalid/hallucinated` branches in `ComputerPlayerKanna`). Kanna gets a nullable setter and no-ops when absent, so the plugin stays usable on the live server outside the harness.

The hallucination rate is the metric that will evaluate custom Modelfiles, so it is first-class rather than a log line.

### 6. `ResultWriter`
Appends one JSON object per line per finished game, flushed immediately. Append-only means a crashed run keeps everything up to the crash, and separate JVM processes can safely write to distinct files that merge trivially.

### 7. `SummaryReporter`
Reads results and prints: win rate with a **Wilson score confidence interval** (not normal-approximation — at N=20 and rates near 0 or 1 the normal interval is badly wrong, and small N is the expected case here), turn-time percentiles, LLM stats, cap-hit rate, error count. Cap-hits and errors are reported **separately from losses** — "never finished" and "lost" mean different things, and folding them together would silently flatter or punish a change.

### 8. `BenchRunner`
`main()`. Parses config, loops games, writes each result immediately, prints a running summary every 5 games so a multi-hour run is observable, prints the full summary at the end.

**Seat swapping:** players swap seats on odd game indices so play/draw advantage cancels. Without this, a 100-game win rate is biased by roughly the magnitude of the effect being measured. `SummaryReporter` accounts for the swap when attributing wins.

---

## Data flow

```
BenchRunner.main(args)
  └─ BenchConfig.parse(args)
  └─ for i in 0..games-1:
       ├─ seed      = baseSeed + i
       ├─ swapped   = (i % 2 == 1)
       ├─ BenchGame.run(config, seed, swapped) ──> GameResult
       │     └─ RandomUtil.setSeed(seed)
       │     └─ TwoPlayerDuel + DeckImporter + PlayerFactory
       │     └─ BenchMetrics attached to Kanna
       │     └─ game.start()
       ├─ ResultWriter.append(result)        [flushed immediately]
       └─ every 5 games: SummaryReporter.printRunning()
  └─ SummaryReporter.printFinal()
```

---

## Error handling

- **Exception inside a game:** caught, recorded as `termination=ERROR` with message and seed, run continues. One card bug must not kill an overnight run, and the recorded seed makes it reproducible in isolation.
- **Turn cap reached:** recorded as `termination=CAP`, counted separately from losses.
- **Ollama unreachable:** fail fast at startup with a preflight `/api/tags` check. This is the one non-recoverable case: Kanna's combat methods catch all throwables and fall back to declaring no attacks/blocks, so an unreachable Ollama would silently degrade every game into a meaningless win rate that *looks* valid. Also verifies the configured model is actually present.
- **Deck fails to load:** fail fast — a typo in a deck name should not produce 20 games of garbage.

---

## Testing

- `SummaryReporterTest` — Wilson interval bounds at known inputs including the degenerate 0% and 100% cases; seat-swap win attribution; cap/error exclusion from win rate.
- `PlayerFactoryTest` — each key builds the expected class; unknown key fails clearly.
- `BenchConfigTest` — CLI parsing, defaults, invalid input.
- `BenchSmokeTest` (in `Mage.Tests`, depends on `Mage.Bench`) — 3 games at a fixed seed, asserting all games terminate and produce parseable results. Deliberately uses `base`/`cp7` players, not Kanna, so it needs no Ollama and can run in CI. Named for the harness rather than for Kanna because it exercises the harness, not the LLM path.

---

## Out of scope

Deliberately excluded to keep this to one implementation cycle:

- Any change to MCTS search quality or rollout strategy (sub-project B).
- The agentic loop, tool definitions, or custom Modelfiles (sub-project C).
- Multi-model comparison sweeps — the config takes one model per run; comparing models means running it twice.
- Web UI or charting for results. JSONL plus a printed summary is enough to drive B and C.

---

## Success criteria

1. `BenchRunner` completes a 20-game `kanna` vs `cp7` run and reports win rate with a confidence interval.
2. A stock-vs-stock control run (`cp7` vs `cp7`) lands near 50% after seat swapping — this validates that the harness itself is unbiased before any Kanna number is trusted.
3. Re-running the same seed and config produces identical results for LLM-free matchups.
4. Results survive a mid-run kill: the JSONL contains every game completed before the kill.
5. The smoke test runs in CI without Ollama.
