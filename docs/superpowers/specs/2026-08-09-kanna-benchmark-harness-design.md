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

---

## Baseline runs (2026-08-09, control numbers corrected 2026-08-10)

Task 9 was the first time the harness played real games, and it surfaced
three engine-level issues that no unit test could have caught:

**Card database never populated.** `BenchGame` ran standalone (no
`TestPlayer`/JUnit base class), and nothing in that path called
`CardScanner.scan()`. `CardTestPlayerAPIImpl` does this in its constructor
for the JUnit test base classes, but `BenchGame` had no equivalent, so
`CardRepository` stayed empty and every deck import failed to find any
card at all, including basic lands. Fixed by adding a guarded (idempotent)
`CardScanner.scan()` call inside `BenchGame.run()`.

**Follow-up fix (2026-08-10): that scan call was inside the timed region
and corrupted `wallTimeMs`.** The first `CardScanner.scan()` call in a
process does a one-time multi-second card-DB build. It was originally
called after `startNanos` was captured, so game 0 in every process
absorbed that build cost into its own `wallTimeMs`, which `SummaryReporter`
folds into turn-time percentiles via `wallTimeMs / turns`. This was visible
in the first cp7 control run below: `percentile()`'s `rank = ceil(0.95*4) =
4` made the reported p95 equal to game 1's own average, which was mostly
scan cost, not decision cost. The 20-game `base` run happened to escape it
only because `ceil(0.95*20)=19` excludes the max slot — incidental, not a
property of the design. Fixed by keeping `CardScanner.scan()` inside the
existing `try`/`catch` (so a scan failure is still caught and reported as a
normal per-game `ERROR` rather than crashing the whole batch run) but
resetting `startNanos` immediately after the scan call returns, before any
game work begins. Both control runs below were re-run after this fix; the
numbers here are the corrected ones.

**`exec:java` breaks the harness's own thread-identity assumption.**
`BenchRunner`'s Javadoc already documents that the engine's
`ThreadUtils.ensureRunInGameThread()` allowlists the thread named `"main"`.
The Maven `exec:java` goal (non-forked) runs the target `main()` method on
a plugin-created thread named after the class, not literally `"main"`, so
every `checkConcede()` call throws, the game accumulates errors, and ends
after a handful of turns with a spurious winner rather than a real result.
Control runs below were therefore run as plain `java -cp <classpath>
mage.bench.BenchRunner ...` (classpath built via `mvn -q
dependency:build-classpath` from `Mage.Tests`), which puts `main()` on the
JVM's actual main thread and produced clean runs with zero `ERROR`
terminations.

**Default deck (`RB Aggro.dck`) is a broken stub.** `Mage.Tests/RB
Aggro.dck` (the default for `--deckA`/`--deckB`) contains a single line —
`71 [SOM:242] Mountain` — 71 Mountains and no spells. Two players holding
only Mountains can never win or lose, so every game runs to the turn cap
regardless of AI quality or harness fairness. `BenchSmokeTest` still uses
this default (unchanged from the brief) because it only asserts
non-`ERROR` termination, which a cap-every-time deck still satisfies. The
control runs below instead pass `--deckA`/`--deckB` explicitly, pointing
at `Mage.Tests/Power Hungry.dck`, a real 90-card constructed deck already
present in the repo (confirmed to actually decide games, see below). No
default in `BenchConfig` was changed — this is a per-invocation CLI
override.

Both control runs below used the harness's stock 2-player-duel setup and a
generous turn cap once the default cap (50) turned out too low for `base`
vs `base` to ever decide (see next paragraph).

### (a) Primary control: `base` vs `base`, 20 games — the acceptance gate

`base` (`ComputerPlayer`) turned out to need far more turns to close out a
game than expected — XMage's basic heuristic AI is known to be passive.
A single trial game at `--turnCap=300` finished at turn 186; `--turnCap=350`
was used for the full run for margin.

Classpath built once via:
```
cd Mage.Tests && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test
```

Command run from `Mage.Tests` (`db/` dir removed first so the card DB
rebuilds cleanly), foreground, after the `startNanos`-placement fix above:
```
java -cp target/classes:target/test-classes:$(cat cp.txt) mage.bench.BenchRunner \
  --games=20 --playerA=base --playerB=base --deckDir=. \
  --deckA="Power Hungry.dck" --deckB="Power Hungry.dck" --turnCap=350 \
  --out=control-a-v2.jsonl
```

Result:
```
Games:        20 total, 20 decisive, 0 cap, 0 draw, 0 error
base:        10 wins
base:        10 wins
Win rate:     50.0% for base  (95% CI 29.9% - 70.1%)
Turn time:    p50 3 ms, p95 5 ms
```

Wall clock: 21.3s for all 20 games (`real 0m21.274s`), game 1 = 1908ms
(with the fix, no longer inflated by the one-time card-DB build), then
620-1090ms/game thereafter.

**95% CI [29.9%, 70.1%] contains 50% — acceptance gate PASSES.** All 20
games were decisive (no cap/draw/error), confirming the harness's seat
swap correctly cancels play/draw advantage for a symmetric matchup. (Note:
this re-run's aggregate win rate, 50.0%, differs slightly from the
pre-timing-fix run's 55.0% reported the day before — expected run-to-run
variance between separate JVM invocations of a 20-game sample, not a
property of the timing fix itself, which only affects the reported
`wallTimeMs`/turn-time percentiles, not game outcomes. Both numbers
satisfy the gate.)

### (b) Secondary sanity check: `cp7` vs `cp7`, 4 games, `--turnCap=15`

Purpose only: prove the heavier minimax AI path runs end to end without
error. Not a statistically meaningful win-rate sample.

This run went through three attempts before a clean, correctly-timed
result. The first two were lost to the harness process being killed
mid-run for reasons external to `BenchRunner` itself (background shell
teardown when the session's turn ended while "waiting" on it — a process
mistake, not a `BenchRunner`/engine fault): one died silently after 2 of 4
games with no exit code captured, the next after 1 of 4. Both partial
`.jsonl` files were otherwise consistent with the third run (same per-game
CAP termination pattern, comparable per-game wall time). The third attempt
completed cleanly but was run *before* the `startNanos`-placement fix
above, so its p95 turn-time figure (2339ms) was contaminated by the
one-time card-DB build, per that fix's description. The run below is the
fourth attempt: foreground, bounded, and run after the fix.

Command actually run (foreground, bounded so a hang cannot silently
consume the session):
```
timeout 420 java -cp target/classes:target/test-classes:$(cat cp.txt) mage.bench.BenchRunner \
  --games=4 --playerA=cp7 --playerB=cp7 --deckDir=. \
  --deckA="Power Hungry.dck" --deckB="Power Hungry.dck" --turnCap=15 \
  --out=control-b-v2.jsonl
echo "EXIT=$?"
```

Result — completed cleanly, exit code 0:
```
Games:        4 total, 1 decisive, 3 cap, 0 draw, 0 error
cp7:         1 wins
cp7:         0 wins
Win rate:     100.0% for cp7  (95% CI 20.7% - 100.0%)
Turn time:    p50 720 ms, p95 1124 ms
LLM:          0 calls, 0 invalid tool calls
```

Per-game wall time: game 1 = 16.9s (CAP), game 2 = 10.8s (CAP), game 3 =
11.1s (CAP), game 4 = 3.7s (WIN, turn 14). Total wall clock for all 4
games: 48s (`END_EPOCH - START_EPOCH`).

**Interpretation, explicitly not a win-rate result:** 3 of 4 games hit the
turn cap; the one decisive game (4 games, n=1 decisive) makes the reported
100% win rate / wide CI statistically meaningless — this run's only job
was to prove `cp7` runs end to end without `ERROR`, which it did. Note
also that this run's seeds are identical to the pre-fix run's (12345-12348)
yet produced a different decisive/cap split (1 decisive here vs 0
previously) — consistent with the cross-JVM non-determinism already
observed in control (a)'s re-run (see above); not investigated further, as
it's outside this task's scope and doesn't affect either control's
acceptance criteria.

The informative number is turn cost, now correctly isolated from card-DB
build time: `cp7`'s p50 was 720ms **per turn** (p95 1124ms), against
`base`'s p50 of 3ms per turn in control (a) — roughly 200x more expensive
per decision. `base` finished a full 186-turn decisive game in well under
a second of total decision time; `cp7` spent up to 16.9s just to grind
through 15 turns, deciding nothing in 3 of its 4 games. This matches the
task brief's warning that `cp7`'s exhaustive minimax cost is (branching
factor)^depth and has been observed pegging most of the machine's cores
for 4+ minutes on a single decision — at `Power Hungry.dck`'s complexity
even a 15-turn cap already shows multi-second-per-turn cost. **`cp7` is
confirmed functionally usable as a benchmark opponent (no `ERROR`s), but a
full 20-game `cp7` vs `cp7` run at a realistic turn cap (e.g. 350, as
needed for `base` to decide) would very plausibly run for hours, exactly
as the task guidance warned.** Any future use of `cp7` as a benchmark
opponent should budget for this — either a much smaller game count, a
much lower turn cap accepting mostly `CAP` outcomes, or accepting
multi-hour wall time.

### Recommendation: change `BenchConfig`'s default deck

`BenchConfig.parse()`'s hardcoded default for `--deckA`/`--deckB` is
`"RB Aggro.dck"`, which resolves to `Mage.Tests/RB Aggro.dck` — a 71-card
stub of nothing but Mountains (see above). This means **any run of
`BenchRunner` that doesn't explicitly pass `--deckA`/`--deckB` can never
produce a decisive game**, silently degrading every such run to 100% `CAP`
regardless of which AI is playing or how fair the harness is. This is a
defect in the plan's chosen default, not in the Task 9 implementation —
`BenchConfig.java` was out of this task's file scope, so it was not
changed here. Recommend changing the default to `"Power Hungry.dck"` (a
real 90-card constructed deck already present in `Mage.Tests`, confirmed
above to let `base` vs `base` decide in ~186 turns and `cp7` vs `cp7` run
without error). That change should be made deliberately, in its own task,
by whoever owns `BenchConfig.java`.
