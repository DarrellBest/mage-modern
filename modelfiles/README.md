# Kanna agentic model profiles

Custom Ollama models tuned for Kanna's tool-calling decisions. Each profile
inherits its base model's weights and chat template and overrides only sampling
parameters and the system prompt.

## Building

```bash
ollama create kanna-qwen3.6        -f modelfiles/kanna-qwen3.6.Modelfile
ollama create kanna-mistral-medium -f modelfiles/kanna-mistral-medium.Modelfile
ollama create kanna-qwen3.5-122b   -f modelfiles/kanna-qwen3.5-122b.Modelfile
```

Creating a profile is cheap — the weight layers are already local, so `ollama
create` only writes a new manifest. Rebuild after editing a Modelfile; the name
is reused, so the old profile is replaced.

## Profiles

| Profile | Base | Size | num_ctx | Intended use |
|---|---|---|---|---|
| `kanna-qwen3.6` | `qwen3.6:latest` (36B MoE) | 23 GB | 32768 | Default. Fastest of the three; first choice if it holds up in an agentic loop. |
| `kanna-mistral-medium` | `mistral-medium-3.5:latest` (127.7B) | 80 GB | 16384 | Capability ceiling comparison. |
| `kanna-qwen3.5-122b` | `qwen3.5:122b` | 81 GB | 16384 | Capability ceiling comparison, Qwen family. |

## Why these parameters

The settings are chosen for *structured tool calling*, which wants very
different sampling from open-ended chat.

**`presence_penalty 0` is the one that matters most.** Stock `qwen3.6` ships
`presence_penalty 1.5`. Presence penalty pushes down tokens that have already
appeared — and a JSON tool call repeats its field names by design (`attacker_id`
and `defender_id` once per attack pair). At 1.5, the model is actively
discouraged from emitting exactly the tokens the schema requires. Every profile
pins it to 0.

**`temperature 0.15`** (down from stock `1` on qwen3.6). Loose sampling is what
produces invented ids like `atk-7` when only `atk-0`..`atk-2` exist. Not 0,
which can push some models into degenerate repetition.

**`top_p 0.90` / `min_p 0.05`** trim the low-probability tail that hallucinated
identifiers come from.

**`repeat_penalty 1.0`** — same reasoning as presence penalty: repetition is
correct here.

**`num_ctx`** is set per model size, not uniformly. The 122B/127B profiles get
16384 rather than 32768 because KV cache dominates memory at that scale. Raise
it when the agentic loop starts carrying whole game histories rather than single
decisions.

The shared `SYSTEM` prompt establishes three things Kanna depends on: act by
calling tools rather than describing actions, use only identifiers that appeared
in the prompt, and treat declining to act as a legitimate move. For
`mistral-medium-3.5` it also displaces the stock "Le Chat assistant" persona,
which biases toward conversational replies where a bare tool call is wanted.

## Measuring whether a profile is actually better

Tool-call reliability is a measurable property, not a matter of taste. Kanna
already detects and rejects hallucinated ids at runtime, and `Mage.Bench`
records that as `invalidToolCalls` per game. To compare profiles, run the
benchmark twice with the same seed and different `--model` values and compare
the invalid-tool-call rate alongside the win rate:

```bash
mvn -q -DskipTests -pl Mage.Bench -am install
cd Mage.Tests && mvn -q exec:java \
  -Dexec.mainClass=mage.bench.BenchRunner \
  -Dexec.classpathScope=test \
  -Dexec.args="--games=20 --model=kanna-qwen3.6:latest --out=qwen36.jsonl"
```

A profile that lowers `invalidToolCalls` without hurting win rate is a real
improvement. A profile that only *feels* smarter in the logs is not.
