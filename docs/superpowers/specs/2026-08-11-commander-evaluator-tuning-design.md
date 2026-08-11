# Commander Evaluator Tuning — Design

**Goal:** Make the Commander bot play Commander rather than 60-card duel. Its evaluator is inherited from MAD unchanged, and every weight in it was calibrated for a 20-life, 60-card, non-singleton format. Correct the parts that are missing model, make the rest tunable, and tune them against measured win rate.

**Constraint (hard):** The Commander bot stays **heuristic-only**. No LLM in the decision loop, no MCTS. The search is MAD's; only the number the search maximises changes. All fork edits stay inside `Mage.Player.AI.Commander` or carry `DARRELLBEST-FORK (keep on merge/rebase from upstream):` markers.

---

## Context

This is sub-project **C**, independent of the Kanna/LLM work.

- **A. Benchmark harness** — complete (`Mage.Bench`), and it is the measurement instrument here too.
- **B. Agentic LLM core** — separate bot, separate track. Renaming `kanna` → `llm` throughout is deferred and will be done as a standalone mechanical change.
- **C. Commander heuristic evaluator** — this spec.

### Decisions carried in from brainstorming

| Question | Decision |
|---|---|
| Bot type | **Heuristic only.** Tuning inherited MAD parameters, not adding learning or LLM |
| What "tuned" is measured against | `commander` (tuned) vs `cp7` (stock MAD), **mirror deck**, seat-swapped |
| Tuning deck | **Krenko** (mono-red aggro) — decides games fast. Not Kairi |
| Deck vs. bot contamination | **Alternate in phases.** Never move both at once |
| Phase 2 shape | Kairi vs Krenko with the **tuned bot on both sides**, so only the decks differ |
| Success bar | Lower bound of the 95% Wilson interval above 50% — not raw win rate |
| Kanna → LLM rename | Deferred, separate change |

---

## The problem, measured

Not inferred — these are real numbers from a live `commander` vs `cp7` Krenko game, printed by `ComputerPlayer6.printBattlefieldScore`:

```
[Seat1], life = 40, score = -5297 (Life:12000, Hand:20, Perm:12926)
[Seat2], life = 30, score =  5297 (Life:11000, Hand:15, Perm:19228)
```

Three defects are visible in that one line.

### 1. The life curve runs entirely in its flat tail

`ArtificialScoringSystem.LIFE_SCORES` is a hardcoded 21-entry table covering 0–20 life. Above 20 it degrades to `LIFE_SCORES[20] + (life - 20) * LIFE_ABOVE_MULTIPLIER`, i.e. a flat 100/life.

In a 40-life format the bot therefore spends the entire first half of every game on a straight line with no curvature:

| Life change | Score cost |
|---|---|
| 40 → 21 (half the life total) | 1,900 |
| 20 → 0 (the other half) | 10,000 |

For scale, one Goblin token in that same game scored **3,690** and a Mountain **405**. The bot will trade ten life for a single creature without hesitating, because ten life is worth a quarter of a Goblin. The steep region of the curve — the part that encodes "I am about to die" — only engages after the player is already half dead.

### 2. Cards in hand are worth nothing

`GameStateEvaluator2.HAND_CARD_SCORE = 5`, against `PERMANENT_SCORE = 300` plus per-permanent dynamic scoring that reaches the thousands. In the game above, four cards in hand scored **20** against **12,926** of permanents — 0.15% of the position.

This is bad in any format and disastrous in Commander, where singleton 100-card decks make each card less replaceable, and where a deck like Kairi wins by holding instant-speed interaction. A bot that values its hand at zero will never hold up a counterspell.

### 3. Commander damage does not exist

The evaluator has no concept of it. Grepping the entire Commander plugin module for commander-damage awareness returns exactly one hit, in the learner's `StateFeatures` — nothing in the scoring path.

This is not a miscalibrated weight, it is **absent representation**. A player at 35 life who has taken 18 commander damage is one connection from losing, and the evaluator scores them as healthy. No value of any existing parameter can express this, which is why the fix must precede the tuning: searching a parameter space over a model that cannot represent the format would fit noise.

Commander tax is missing for the same reason — recasting cost grows {2} per prior cast, so neither losing a commander nor repeatedly recasting one is free, and the bot cannot see either.

### 4. Rarity as a card-quality proxy

`getCardDefinitionScore` scores non-creatures partly by `rarity.getRating() * 30`. Noise in singleton, listed for completeness — lowest priority.

---

## Architecture

### Stage 1 — Fix the model (right answers, not search answers)

These are corrections, not tunables. Each still lands as a *parameterised* correction so Stage 2 can tune its shape.

| Change | What it does |
|---|---|
| Life curve spans starting life | Replace the fixed 21-entry table with a curve generated from the format's starting life, preserving the original intent: marginal life cheap while healthy, expensive near death |
| Commander damage term | Score per-opponent commander damage against the 21 threshold as a second lethal axis, parallel to life |
| Commander tax term | Value a commander's survival and penalise repeated recasting, using the actual cast count |
| Hand value | Give `HAND_CARD_SCORE` a defensible magnitude relative to permanent scoring |

### Stage 2 — Make the constants tunable

An immutable `CommanderEvalParams` carrying every scoring weight, **with defaults exactly equal to today's values**. Threaded through `GameStateEvaluator2` and `ArtificialScoringSystem`; held by `ComputerPlayerCommander`; loadable from a file so a sweep needs no recompile. `BenchConfig` gains `--paramsA` / `--paramsB`.

**Correctness trap to check explicitly:** these scoring methods are `public static` today, and the search runs through simulated copies of the player (`SimulatedPlayer2`, and the copy constructors on `ComputerPlayer6`/`7`). If a simulated copy does not carry the params object, the bot would *search* with default weights while *evaluating* with tuned ones — a silent, plausible-looking corruption. The copy path must be verified, not assumed.

`ComputerPlayerLearner` already routes evaluation through an overridable evaluator; params should compose with that extension point rather than introduce a parallel mechanism.

### Stage 1.5 — Two structural defects found while inventorying (fix before tuning)

**a. One evaluation was never routed through `evaluateState`.** `ComputerPlayer7.java:237` sets
`currentScore` by calling the static `GameStateEvaluator2.evaluate(...)` directly, while
`ComputerPlayer6.java:357-360` compares it against `testScore` from the *overridable*
`evaluateState` and prunes the branch on the result. The other 13 in-search evaluations were
deliberately hoisted into `evaluateState`; this one was missed.

This is already a live bug for `ComputerPlayerLearner`, which compares its blended learned score
against a hand-tuned one — the exact incoherent comparison `ComputerPlayer6`'s javadoc (`:160-167`)
claims was eliminated. Once `CommanderEvalParams` exists it becomes the same bug for every tuned
bot: the search would prune by comparing tuned scores against default-weighted ones. **Route it
through `evaluateState` regardless of whether the params work proceeds.**

**b. The copy-constructor omission is a demonstrated silent-fallback trap.** `ComputerPlayer6`'s
copy constructor (`:130-141`) copies `maxDepth`, `currentScore`, `combat`, `actions`, `targets`,
`choices`, `actionCache` — and silently omits `maxNodes` and `maxThinkTimeSecs`, which are set only
in the `(name, range, skill)` constructor.

This is inherited verbatim from upstream MAD and is **not currently firing**: live game logs show
`Nodes calculated: 31` terminating on the depth limit rather than the node limit, so the instance
actually playing carries a non-zero `maxNodes`. It is recorded here because it proves the failure
mode is real and unguarded — add a field to `ComputerPlayer6`, forget the copy constructor, and the
bot silently plays with defaults while appearing to accept a tuned config. `CommanderEvalParams`
must be added to all copy constructors (`ComputerPlayer6`, `ComputerPlayer7`,
`ComputerPlayerCommander`, `ComputerPlayerLearner`, `ComputerPlayerControllableProxy`) **by
reference** (it is immutable, matching `ComputerPlayerLearner`'s existing `federation`/`session`
idiom), with a test asserting a copy carries non-default params.

**Good news on the search path:** `SimulatedPlayer2` never evaluates — it extends `ComputerPlayer`,
not `ComputerPlayer6`, and holds zero references to either scoring class. `SimulationNode2` holds
only a `UUID`. The object doing the searching and scoring is always the real player, so a params
field on it is used correctly throughout the search.

**Awkward seam to resolve explicitly:** `util/CombatUtil` reaches the evaluator from 5 static call
sites, four frames deep from `ComputerPlayer6.declareBlockers`. All five evaluate from
`defendingPlayerId`'s perspective, read from `game.getCombat().getDefenders()` — not necessarily the
calling bot. Threading params there means the bot models blocking with its own weights, which is
probably what we want but is not what the signature says. Decide it deliberately.

### Stage 3 — Measurement

- **Parallel bench wrapper.** N `BenchRunner` JVMs on disjoint seed ranges, merged JSONL, one pooled Wilson interval. Each JVM must be a separate process — the engine's `ThreadUtils.ensureRunInGameThread()` allowlists a thread literally named `main`, so in-JVM parallelism and Maven `exec:java` both silently corrupt games. Pooled rate is computed over merged raw results, never by averaging per-worker rates.
- **Log suppression for bulk runs.** The AI logs at INFO at roughly 400KB per 20 seconds of play. Across thousands of games that is gigabytes and plausibly a throughput cost. Suppress `mage.player.ai` to WARN for sweeps via a separate log4j config selected by JVM flag, leaving the default developer experience and `mage.bench`'s own messages intact.

---

## Measured budget

From a completed 10-game `cp7` vs `commander` Krenko Commander run:

| Metric | Value |
|---|---|
| Games | 10, all decisive (0 cap, 0 draw, 0 error) |
| Wall clock | 10m40s |
| Per-game | 10s – 149s, **median ~58s** |
| Turn time | p50 2,794 ms, p95 9,912 ms |
| CPU per JVM | ~1.5 cores (measured 149%) |
| Machine | 24 cores, 62 GB RAM, shared with XMage server + Ollama |

So ~12 concurrent workers is the safe ceiling, giving roughly **1,000 games in ~90 minutes**. A serial 1,000-game run would be ~18 hours.

Kairi was tried first as the tuning deck and **failed outright**: a Kairi mirror at `--turnCap=60` produced **zero completed games in 20 minutes**. Mono-blue draw-go mirrored against itself, piloted by two bots that value cards in hand at zero, does not resolve. This is the empirical reason the tuning fixture is Krenko.

---

## Protocol

**Phase 1 — tune the bot.** Krenko mirror, `commander` (tuned) vs `cp7` (stock MAD), seat-swapped by the harness. Deck frozen.

**Phase 2 — iterate the deck.** Params frozen at the Phase 1 winner. Kairi vs Krenko with the **tuned bot on both sides**, so the decks are the only difference. Compare Kairi versions against each other.

Phases never overlap. Moving deck and parameters together makes any win-rate delta unattributable.

### Gates

| Gate | Test | Why |
|---|---|---|
| **G0 — instrument** | Identical params both sides → 95% CI must contain 50% | Proves the harness is unbiased and that the params plumbing changes nothing when defaults are used |
| **G1 — improvement** | Tuned vs MAD → **lower bound** of the 95% CI above 50% | A raw win rate above 50% at small n is consistent with no effect |
| **G2 — generalisation** | Final candidate re-confirmed on a second deck (Edgar or Prossh) | Guards against fitting mono-red aggro |

### Baseline already established

`cp7` vs `commander` as the code stands today: **6–4 to cp7, 60%, 95% CI [31.3%, 83.2%]**, 10 games, all decisive. The interval contains 50%, so the two behavioural commits already on the Commander fork (`break no-progress priority loops`, `cap chained activations of the same ability at 3`) show **no evidence of regression**. Note this is *not* a null control — the fork's `ComputerPlayer7` differs from MAD's by ~120 lines. The true null control is G0, which Stage 2 provides by construction.

---

## Risks

| Risk | Mitigation |
|---|---|
| Overfitting to Krenko mirror | G2 on a second deck before declaring an improvement |
| Simulated copies losing params | Explicitly verify the copy path; a test that asserts a simulated copy carries non-default params |
| Sweep finds noise | G1 uses the CI lower bound, not the point estimate |
| Tuning on top of an unmeasured regression | Baseline above already rules this out at n=10 |
| Two bots that both ignore hand value make hand-value tuning unmeasurable in a mirror | Phase 1 is tuned-vs-MAD, not tuned-vs-tuned, so the asymmetry is the signal |

## Out of scope

- Multiplayer / free-for-all. `GameStateEvaluator2.evaluate` is single-opponent by construction (`// TODO: add multi opponents support`) and `--gameType=commander` is a duel. 1v1 only.
- Any change to MAD itself, or to the LLM bot.
- The `kanna` → `llm` rename.
- Learning / federated weights — `ComputerPlayerLearner` exists but is a separate track.
