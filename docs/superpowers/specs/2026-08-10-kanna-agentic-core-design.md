# Kanna Agentic Core — Design

**Goal:** Rebuild Kanna as a fully agentic, local-LLM-driven Magic player in which heuristics and the model work as one system. Heuristics compute — exact combat math, legality, mana availability, concrete consequences. The model judges — which line matters, what plan the game is on, whether a trade is worth taking. No search anywhere.

**Constraint (hard):** No Monte Carlo Tree Search, no minimax. Kanna stops extending `ComputerPlayerMCTS`. All fork edits stay inside the Kanna plugin module or carry `DARRELLBEST-FORK (keep on merge/rebase from upstream):` markers.

---

## The central principle: computation and judgment are complementary

The two halves are not a primary and a fallback. Each does what the other is bad at:

| Heuristics are good at | The model is good at |
|---|---|
| Exact combat math — who dies, how much gets through, is it lethal | Whether a losing trade is worth taking anyway |
| Enumerating every legal option without missing one | Knowing which three of forty options matter |
| Mana availability and cost payment that keeps a plan viable | What the plan is |
| Never making an arithmetic error | Reading the game — racing, stabilising, holding up removal |

So heuristics do not sit behind the model waiting for it to fail. They run *first*, on every decision, and hand the model a position already annotated with computed consequences. The model then chooses. When the model fails or stalls, the same heuristics that fed it also stand in for it — but that is a side effect of their being there, not their purpose.

Concretely, the model never sees "Grizzly Bears (2/2) can attack." It sees "attacking with Grizzly Bears trades with Hill Giant and deals 0 damage" — and decides whether that serves the plan.

---

## Context

This is sub-project **B**. It supersedes the earlier "improve MCTS search quality" direction, which is abandoned: the project is not improving search, it is removing it.

- **A. Benchmark harness** — complete (`Mage.Bench`). Unaffected by this pivot and still the measurement instrument. Control run `base` vs `base` gives 50.0%, 95% CI [29.9%, 70.1%], so the instrument is not seat-biased.
- **B. Agentic core with integrated heuristics** — this spec.

### Decisions carried in from brainstorming

| Question | Decision |
|---|---|
| Search | **Removed.** Kanna extends `ComputerPlayer`, not `ComputerPlayerMCTS` |
| LLM scope | Actions, targeting, attacks, blocks. Mechanical sub-choices stay heuristic |
| Loop shape | Multi-step within a single decision: read-only inspection tools, then one commit, hard-capped |
| Identity | Kanna *is* the agentic player — rewritten in place, not a new player type |
| Heuristics | Integral, not a separate phase. They annotate every decision the model sees, execute what it chooses, and cover it when it fails |
| Speed | Deprioritised. Strength first |

---

## Why `ComputerPlayer` is the base class

`ComputerPlayer` (1,355 lines, ~17 decision-shaped callbacks) supplies working implementations for everything Kanna does not drive: mana payment, trigger ordering, pile choices, mode selection. Extending it means the agent owns judgment while the mechanics keep working — and gives the heuristic layer something real to improve on rather than build from nothing.

**Trap to avoid:** `ComputerPlayer.priority()` is a no-op that just calls `pass(game)` — "minimum implementation for do nothing". This is the exact trap the original Kanna hit with `ComputerPlayer6`, diagnosed in commit `f1e0ea29e5`. Kanna **must** override `priority()`; inheriting it means passing every window forever.

---

## Architecture

Today's `ComputerPlayerKanna` is ~550 lines doing HTTP, prompt assembly, JSON-schema construction, validation, and history inline. That does not survive this growth. It decomposes into single-responsibility units, split into the two halves that feed each other:

**Judgment side**

| Component | Responsibility |
|---|---|
| `ComputerPlayerKanna` | Engine callbacks only: translate a callback into a decision request, apply the result |
| `KannaAgent` | The loop: prompt → model → inspect or commit → validate → return |
| `OllamaClient` | HTTP, tool-schema assembly, response parsing, retry |
| `InspectionTools` | Read-only tool handlers answered locally from `Game` |

**Computation side**

| Component | Responsibility |
|---|---|
| `ActionCatalog` | Legal actions → stable short ids; ids back to `ActivatedAbility` |
| `CombatEvaluator` | Exact combat math: per-attack and per-block outcomes, damage through, lethal detection |
| `ActionRanker` | Score and shortlist actions, annotated with computed consequences |
| `ManaPlanner` | Pay costs so the colors still needed in hand stay available |
| `GameStateFormatter` | `Game` + computed annotations → the text the model reads |

`ComputerPlayerKanna` becoming thin is the point: it should read as four callbacks delegating to an agent, not as an AI.

---

## The decision loop

```
engine callback
  └─ trivial? ──yes──> act without calling the model
  └─ no
       ├─ ActionCatalog.build(game)              → ids + legal options
       ├─ CombatEvaluator.evaluate(...)          → exact outcomes per option
       ├─ ActionRanker.shortlist(...)            → ranked top-N + computed reasons
       ├─ GameStateFormatter.render(...)         → annotated board text
       └─ KannaAgent.decide(context)
            loop, max 4 tool calls:
              model calls an inspection tool → answer locally, continue
              model calls the commit tool    → validate → return
            on cap / invalid / no tool call → retry once → heuristic decides
       └─ ManaPlanner pays any costs the chosen action incurs
```

### Trivial-decision bypass

If `getPlayable(game, true)` yields only `PassAbility`, Kanna passes **without an LLM call**. Most priority windows in Magic are exactly this. This is not an optimisation — without it the design is unusable, because every trivial pass would cost a model round trip.

### The cap

Four tool calls per decision. On reaching the cap without a commit, the agent retries once with an explicit "you must commit now" instruction, then the heuristic layer decides.

### Failure is covered by the same heuristics that fed the prompt

Today every Kanna failure path declares no attacks and no blocks. Combined with the measured **~1-in-6 rate of responses containing no tool call at all**, roughly one combat in six is a phantom pass recorded in history as a deliberate decision — poisoning the history the model reads on later turns.

New behavior, in order: **retry once → `CombatEvaluator`/`ActionRanker` pick the top-ranked option → record via `recordInvalidToolCall()`**. Because the heuristics already computed and ranked every option to build the prompt, the fallback costs nothing extra and is strictly better than base `ComputerPlayer`: it will not attack into lethal blocks or chump-block when it does not need to.

---

## `CombatEvaluator` — the shared calculator

One component serves three consumers, which is what makes the halves complement rather than duplicate:

1. **Annotating the prompt** — every candidate attack and block is labelled with its computed outcome.
2. **Ranking** — `ActionRanker` orders combat options by that same math.
3. **Fallback** — when the model fails, the top-ranked option is already computed.

For each candidate it computes: which creatures die on each side (accounting for first/double strike, deathtouch, and trample), damage that gets through, whether the attack is lethal, and whether the defender has a blocking assignment that survives. It reads `flying`, `reach`, `menace`, `deathtouch`, `first strike`, `double strike` — the six keywords that decide most combats.

It is pure: `(Game, candidate) → outcome`. No mutation, no engine callbacks, fully unit-testable against constructed board states.

---

## Ranked action shortlist

`getPlayable()` routinely returns dozens of options, most of them noise. `ActionRanker` scores them and presents a ranked shortlist annotated with computed consequences:

```
 1. act-0  Lightning Bolt -> Hill Giant   (kills it; removes their only untapped blocker)
 2. act-4  Attack with Serra Angel        (unblockable - no flier or reach; 4 damage, they go to 8)
 3. act-1  Play Mountain                  (3 lands untapped, enables Flame Slash)
 ... 34 more options: call show_all_actions
```

Every parenthetical is computed, not asserted. This cuts prompt tokens, anchors decision quality, and keeps the full list reachable.

**The shortlist must never hide a legal action.** `show_all_actions` always exposes the full set, and the count of hidden options is always stated.

---

## `ManaPlanner` — executing the model's intent without breaking it

The model chooses *what* to do; paying for it badly can invalidate the plan. Base `ComputerPlayer` pays costs first-fit, which can strand a color still needed by a card in hand.

`ManaPlanner` pays costs preferring sources that keep the most remaining hand cards castable. It never reaches the model — it exists so that what the model decided actually stays possible. Invisible in a log; visible as fewer wasted turns.

---

## Tools

**Read-only inspection** (answered locally from `Game`, never mutate state):

| Tool | Returns |
|---|---|
| `get_card_text(id)` | Full oracle text of a permanent or card in hand |
| `get_zone(player, zone)` | Contents of graveyard / exile / battlefield |
| `get_my_hand()` | Full hand with costs and text |
| `show_all_actions()` | The complete legal-action list when the shortlist is not enough |
| `evaluate_combat(attacks)` | `CombatEvaluator` run on a hypothetical assignment the model is considering |

`evaluate_combat` is the clearest expression of the principle: the model asks the calculator a what-if, gets exact math back, and decides.

**Commit** (exactly one per decision, terminates the loop):

| Tool | Used at |
|---|---|
| `choose_action(action_id)` | Priority |
| `choose_targets(target_ids)` | Target selection |
| `declare_attackers(attacks)` | Declare attackers |
| `declare_blockers(blocks)` | Declare blockers |

Ids are short synthetic strings (`atk-0`, `act-3`, `def-1`), never raw UUIDs — the existing combat code already does this because UUIDs are error-prone for a model to echo back.

---

## Prompt contract

Every decision prompt carries: Kanna's life and board, the opponent's life and board (untapped creatures with P/T and the six combat-relevant keywords), the ranked and annotated shortlist, and up to 5 lines of Kanna's own recent decision history. History stays capped because it is re-sent with every prompt and is therefore a recurring token cost, not a one-time one.

---

## Model configuration

Default `xmage-ai-qwen3.6:latest` — a custom Ollama profile in `modelfiles/`. Measured against stock `qwen3.6`: **5.6s vs 45.7s mean latency**, driven mostly by `presence_penalty 0` (stock ships 1.5, which penalises the repeated JSON field names a tool schema requires) and `temperature 0.15` (stock ships 1).

Model and URL are already instance fields with setters, so a benchmark run targets a profile without a rebuild.

---

## Error handling

- **No tool call in response** — retry once, then heuristics decide, count via `recordInvalidToolCall()`.
- **Hallucinated id** — dropped, counted, decision continues with remaining valid entries. Existing behavior, retained.
- **Commit fails validation entirely** — treat as no tool call.
- **Ollama unreachable mid-game** — fall back to heuristics for the rest of the game and log once, loudly. Do not fail the game; the invalid-call count makes the degradation obvious in harness results.
- **Cap reached** — retry once with a commit-now instruction, then heuristics decide.

---

## Testing

| Test | Verifies |
|---|---|
| `CombatEvaluatorTest` | Trades, first/double strike, deathtouch, trample, menace, flying-vs-reach, lethal detection — against constructed board states |
| `ActionCatalogTest` | Id assignment is stable and round-trips to the right `ActivatedAbility`; ids are never reused within a decision |
| `ActionRankerTest` | Ranking order for known board states; shortlist never omits an action from the full list; hidden count is accurate |
| `ManaPlannerTest` | Payment preserves colors needed by cards still in hand; falls back cleanly when no such payment exists |
| `GameStateFormatterTest` | Board rendering includes untapped creatures and keywords, excludes tapped creatures, carries annotations |
| `OllamaClientTest` | Parses tool calls; handles no-tool-call, malformed JSON, non-2xx; retry fires exactly once |
| `KannaAgentTest` | Inspection-then-commit completes; cap triggers retry then heuristic fallback; hallucinated ids dropped and counted |
| Integration | `Mage.Bench` run: `kanna` vs `base` completes with zero `ERROR` terminations |

Everything except the integration test runs without Ollama — the whole computation side is pure and the agent tests use canned responses. The suite stays CI-safe.

---

## Out of scope

- Any change to `Mage.Bench`.
- Removing `ComputerPlayerMCTS` or `ComputerPlayer7` from the tree — they remain as benchmark opponents (`mcts`, `cp7`).
- Streaming responses, multi-model routing, fine-tuning.
- Trigger-ordering improvements: inherited behavior is kept for now. Listed here explicitly because it was discussed and deliberately deferred — `CombatEvaluator` and `ManaPlanner` carry the heuristic value; trigger order is low-yield by comparison.

---

## Success criteria

1. Kanna plays a complete game against `base` through `Mage.Bench` with zero `ERROR` terminations.
2. `priority()` is genuinely agentic: a game log shows Kanna casting spells and playing lands chosen by the model, not passing every window.
3. Trivial-pass bypass works: LLM call count per game is materially lower than the number of priority windows.
4. A response with no tool call never silently becomes a pass — it appears in `invalidToolCalls`.
5. `CombatEvaluator`'s math is verified against hand-worked board states covering every keyword it reads.
6. The heuristic fallback alone (Ollama deliberately stopped) plays a complete game and beats base `ComputerPlayer` head to head — proving the computation side is a real player, not a stub.
7. The unit suite passes without Ollama running.

**Deliberately not a criterion:** no baseline of the outgoing MCTS-based Kanna is captured. Because Kanna is rewritten in place, that comparison becomes impossible once the rewrite lands — this is a knowing, accepted loss. The agentic Kanna is judged against `base` and `cp7`, not against what it replaced.

---

## First agentic games (2026-08-10)

Kanna played complete games of Magic against the stock `base` AI, driven by
`xmage-ai-qwen3.6` running locally. Two runs, same seed and decks, before and
after a fix wave.

Command (from `Mage.Tests`, classpath via `mvn dependency:build-classpath`):

```
java -Dlog4j.configuration=file:<demo-log4j.properties> \
  -cp target/classes:target/test-classes:$(cat cp.txt) mage.bench.BenchRunner \
  --games=1 --playerA=kanna --playerB=base --deckDir=. \
  --deckA="Power Hungry.dck" --deckB="Power Hungry.dck" --turnCap=20 \
  --model=xmage-ai-qwen3.6:latest --out=<results.jsonl>
```

`exec:java` must NOT be used — it runs `main()` off a thread not named `"main"`,
which breaks `ThreadUtils.ensureRunInGameThread()` and silently corrupts results.

### Result

| | Run 1 (pre-fix) | Run 2 (post-fix) |
|---|---|---|
| Model-driven plays | 18 | 8 |
| Repeated-activation spins | 6 | **0** |
| Jar of Eyeballs activated at X=0 | 5 | **0** |
| Attacks declared | 5 | 4 |
| Heuristic fallbacks | 1 | 4 |
| Cap exhaustions | 0 | 3 |
| Transport timeouts | 1 | 0 |
| Outcome | killed (spinning) | **WIN, 17 turns, 141.8 s, 0 errors** |

Run 2: `winner=kanna, turns=17, 141.8s, termination=WIN, llm_calls=25`.

**This establishes that the machinery works end to end. It establishes nothing
about strength.** n=1, and the harness correctly reports a 95% CI of
20.7%–100%. Three of twelve decisions also went to the heuristic fallback, so
the win is partly the fallback's — one of its picks (Curse of Shallow Graves)
generated the Zombie tokens that did much of the damage.

### Defects the first game found that 76 unit tests did not

1. **`activateAbility`'s return was discarded**, so a failed activation left the
   same action top-ranked and the model re-picked it — one LLM call per spin.
   Observed six times in one game. *Had been flagged as Important in review and
   dropped before reaching a fix round.*
2. **30 s HTTP timeout became incoherent** once `num_predict` rose to 8192.
3. **Prompt/thinking/token logging was lost in the rewrite**, so traces showed
   what Kanna did and never why.
4. **Activated abilities were rendered without the state that determines their
   value** — Jar of Eyeballs shown without its counter count, so the model could
   not tell X was 0.
5. **`ActionRanker` scored unrecognised abilities above passing**, making a
   valueless ability the top suggestion whenever nothing better existed.

### Known-open at time of writing

`get_card_text` is advertised to the model but returns the shortlist label it
already has (priority path) and is unhandled entirely (targeting path, where it
is treated as an unknown tool → immediate fallback plus a **false**
`invalidToolCall`). This caused all three cap exhaustions in run 2 and corrupts
the metric intended to measure model quality. Fix before trusting any benchmark
run's invalid-call numbers.

### Standing lesson

Every defect above was found by reading or by playing, never by the unit suite,
and two had been assigned low severity from a diff. Severity judged by reading a
diff has been a poor predictor of severity in play — findings about *what the
model is shown or given* have consistently outranked their labels.
