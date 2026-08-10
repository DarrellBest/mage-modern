# Kanna Agentic Core — Design

**Goal:** Rebuild Kanna as a fully agentic, local-LLM-driven Magic player. The model makes every strategic decision — which action to take at priority, what to target, how to attack and block — reasoning with read-only inspection tools before committing. No search anywhere.

**Constraint (hard):** No Monte Carlo Tree Search, no minimax. Kanna stops extending `ComputerPlayerMCTS`. All fork edits stay inside the Kanna plugin module or carry `DARRELLBEST-FORK (keep on merge/rebase from upstream):` markers.

---

## Context

This is sub-project **B1**. It supersedes the earlier "B. Search quality" direction, which is abandoned: the project is not improving MCTS, it is removing it.

- **A. Benchmark harness** — complete (`Mage.Bench`). Unaffected by this pivot and still the measurement instrument. Control run `base` vs `base` gives 50.0%, 95% CI [29.9%, 70.1%], so the instrument is not seat-biased.
- **B1. Agentic core** — this spec.
- **B2. Heuristic layer** — next spec: stronger fallback combat evaluator, mana payment that preserves colors, trigger ordering. No LLM involved; separable and unit-testable without Ollama.

### Decisions carried in from brainstorming

| Question | Decision |
|---|---|
| Search | **Removed.** Kanna extends `ComputerPlayer`, not `ComputerPlayerMCTS` |
| LLM scope | Actions, targeting, attacks, blocks. Mechanical sub-choices stay on inherited heuristics |
| Loop shape | Multi-step within a single decision: read-only inspection tools, then one commit, hard-capped |
| Identity | Kanna *is* the agentic player — rewritten in place, not a new player type |
| Heuristics | Ranked action shortlist lives here (it defines the prompt contract); fallback and sub-choice work goes to B2 |
| Speed | Deprioritised. Strength first |

---

## Why `ComputerPlayer` is the base class

`ComputerPlayer` (1,355 lines, ~17 decision-shaped callbacks) supplies working implementations for everything Kanna does not drive: mana payment, trigger ordering, pile choices, mode selection. Extending it means the agent owns strategy while the mechanics keep working.

**Trap to avoid:** `ComputerPlayer.priority()` is a no-op that just calls `pass(game)` — "minimum implementation for do nothing". This is the exact trap the original Kanna hit with `ComputerPlayer6`, diagnosed in commit `f1e0ea29e5`. Kanna **must** override `priority()`; inheriting it means passing every window forever.

---

## Architecture

Today's `ComputerPlayerKanna` is ~550 lines doing HTTP, prompt assembly, JSON-schema construction, validation, and history inline. That does not survive this growth. It decomposes into single-responsibility units:

| Component | Responsibility | Depends on |
|---|---|---|
| `ComputerPlayerKanna` | Engine callbacks only: translate a callback into a `Decision`, apply the result | `KannaAgent` |
| `KannaAgent` | The loop: prompt → model → inspect or commit → validate → return | `OllamaClient`, `InspectionTools`, `GameStateFormatter` |
| `OllamaClient` | HTTP, tool-schema assembly, response parsing, retry | — |
| `GameStateFormatter` | `Game` → the text the model reads | — |
| `ActionCatalog` | Legal actions → stable short ids; ids back to `ActivatedAbility` | — |
| `ActionRanker` | Score and shortlist legal actions | `GameStateFormatter` |
| `InspectionTools` | Read-only tool handlers answered locally from `Game` | — |
| `DecisionMetrics` | Existing instrumentation interface (harness sink) | — |

`ComputerPlayerKanna` becoming thin is the point: it should read as four callbacks delegating to an agent, not as an AI.

---

## The decision loop

Every decision Kanna owns follows one path:

```
engine callback
  └─ trivial? ──yes──> act without calling the model
  └─ no
       ├─ ActionCatalog.build(game)        → ids + legal options
       ├─ ActionRanker.shortlist(...)      → ranked top-N with reasons
       ├─ GameStateFormatter.render(...)   → board text
       └─ KannaAgent.decide(context)
            loop, max 4 tool calls:
              model calls an inspection tool → answer locally, continue
              model calls the commit tool    → validate → return
            on cap / invalid / no tool call → retry once → fall back to super
```

### Trivial-decision bypass

If `getPlayable(game, true)` yields only `PassAbility`, Kanna passes **without an LLM call**. Most priority windows in Magic are exactly this. This is not an optimisation — without it the design is unusable, because every trivial pass would cost a model round trip.

### The cap

Four tool calls per decision. On reaching the cap without a commit, the agent retries the decision once with an explicit "you must commit now" instruction, then falls back to `super`'s heuristic.

### Failure falls back to heuristics, never to nothing

Today every Kanna failure path declares no attacks and no blocks. Combined with the measured **~1-in-6 rate of responses containing no tool call at all**, that means roughly one combat in six is a phantom pass recorded in history as a deliberate decision — poisoning the history the model reads on later turns.

New behavior, in order: **retry once → fall back to `super`'s heuristic → record via `recordInvalidToolCall()`**. A failure becomes a visible metric and a reasonable move, never a silent pass.

---

## Tools

**Read-only inspection** (answered locally from `Game`, never mutate state):

| Tool | Returns |
|---|---|
| `get_card_text(id)` | Full oracle text of a permanent or card in hand |
| `get_zone(player, zone)` | Contents of graveyard / exile / battlefield |
| `get_my_hand()` | Full hand with costs and text |
| `show_all_actions()` | The complete legal-action list when the shortlist is not enough |

**Commit** (exactly one per decision, terminates the loop):

| Tool | Used at |
|---|---|
| `choose_action(action_id)` | Priority |
| `choose_targets(target_ids)` | Target selection |
| `declare_attackers(attacks)` | Declare attackers |
| `declare_blockers(blocks)` | Declare blockers |

Ids are short synthetic strings (`atk-0`, `act-3`, `def-1`), never raw UUIDs — the existing combat code already does this because UUIDs are error-prone for a model to echo back.

---

## Ranked action shortlist

`getPlayable()` routinely returns dozens of options, most of them noise. `ActionRanker` scores them and presents a ranked shortlist with one-line reasons, e.g.:

```
 1. act-0  Lightning Bolt -> Hill Giant   (removes their only untapped blocker)
 2. act-1  Play Mountain                  (untapped mana, enables 3-drop)
 3. act-2  Grizzly Bears                  (board presence)
 ... 34 more options: call show_all_actions
```

This cuts prompt tokens, anchors decision quality, and keeps the full list reachable. Ranking is heuristic and deliberately simple in B1 — land drops, removal, creatures, then everything else. B2 deepens it.

**The shortlist must never hide a legal action.** `show_all_actions` always exposes the full set, and the count of hidden options is always stated.

---

## Prompt contract

Every decision prompt carries: Kanna's life and board, the opponent's life and board (untapped creatures with P/T and the six combat-relevant keywords — flying, reach, menace, deathtouch, first strike, double strike), the ranked shortlist, and up to 5 lines of Kanna's own recent decision history. History stays capped because it is re-sent with every prompt and is therefore a recurring token cost, not a one-time one.

---

## Model configuration

Default `xmage-ai-qwen3.6:latest` — a custom Ollama profile in `modelfiles/`. Measured against stock `qwen3.6`: **5.6s vs 45.7s mean latency**, driven mostly by `presence_penalty 0` (stock ships 1.5, which penalises the repeated JSON field names a tool schema requires) and `temperature 0.15` (stock ships 1).

Model and URL are already instance fields with setters, so a benchmark run targets a profile without a rebuild.

---

## Error handling

- **No tool call in response** — retry once, then fall back to `super`, count via `recordInvalidToolCall()`.
- **Hallucinated id** — dropped, counted, decision continues with remaining valid entries. Existing behavior, retained.
- **Commit fails validation entirely** — treat as no tool call.
- **Ollama unreachable mid-game** — fall back to `super` for the rest of the game and log once, loudly. Do not fail the game; the harness records the invalid-call count, which will make the degradation obvious in results.
- **Cap reached** — retry once with a commit-now instruction, then fall back.

---

## Testing

| Test | Verifies |
|---|---|
| `ActionCatalogTest` | Id assignment is stable and round-trips to the right `ActivatedAbility`; ids are never reused within a decision |
| `ActionRankerTest` | Ranking order for known board states; shortlist never omits an action from the full list; hidden count is accurate |
| `GameStateFormatterTest` | Board rendering includes untapped creatures and keywords, excludes tapped creatures |
| `OllamaClientTest` | Parses tool calls; handles no-tool-call, malformed JSON, non-2xx; retry fires exactly once |
| `KannaAgentTest` | Inspection-then-commit completes; cap triggers retry then fallback; hallucinated ids are dropped and counted |
| Integration | `Mage.Bench` run: `kanna` vs `base` completes with zero `ERROR` terminations |

`OllamaClientTest` and `KannaAgentTest` run against canned responses — no Ollama required, so the suite stays CI-safe.

---

## Out of scope

- The B2 heuristic layer: fallback combat evaluator, mana payment, trigger ordering.
- Any change to `Mage.Bench`.
- Removing `ComputerPlayerMCTS` or `ComputerPlayer7` from the tree — they remain as benchmark opponents (`mcts`, `cp7`).
- Streaming responses, multi-model routing, fine-tuning.

---

## Success criteria

1. Kanna plays a complete game against `base` through `Mage.Bench` with zero `ERROR` terminations.
2. `priority()` is genuinely agentic: a game log shows Kanna casting spells and playing lands chosen by the model, not passing every window.
3. Trivial-pass bypass works: LLM call count per game is materially lower than the number of priority windows.
4. A response with no tool call never silently becomes a pass — it appears in `invalidToolCalls`.
5. The unit suite passes without Ollama running.
6. A baseline harness run of the **current MCTS-based Kanna** is recorded *before* the rewrite begins, and the agentic Kanna is compared against it on the same seeds and deck. Because Kanna is rewritten in place, the old implementation stops existing the moment the rewrite lands — so this baseline must be captured first or it is lost permanently. The comparison is recorded whichever way it falls.
