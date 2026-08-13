#!/usr/bin/env python3
"""Train the commander model from FINISHED games, learning from every seat's real result.

    tools/train-from-games.py ai-audit.jsonl [--apply] [--epochs N]

Reads FEATURES snapshots (one per seat per turn) and RESULT records (who actually won),
joins them on game id, and fits the model toward the true outcome for every player --
winner and losers alike.

WHY THIS EXISTS, AND WHY IT BEATS THE ONLINE LEARNER

The online learner (OnlineTDLearner) learns only from games a ComputerPlayerLearner
actually played, and bootstraps its target from its OWN prediction. Two consequences:

  * A human playing against Computer - commander produced NO training data at all, which
    threw away the most valuable games available. A human winning is a demonstration of a
    line worth imitating, and self-play cannot manufacture those.
  * TD bootstrapping moves the model toward what it already believes. The code's own
    comment calls this out: "reinforcing whatever it already believed".

A finished game supplies the real answer for every seat in it. That is a supervised label,
not a guess, and it is available for humans, for MAD, for any player at the table.

The label is the seat's actual result, so the model learns what winning positions look
like from the WINNER's perspective and what losing ones look like from the loser's. It is
imitation by outcome rather than by move, which is the honest thing a position evaluator
can learn -- it predicts "is this side winning", not "what would a human play here".

MERGING IS DELTA-BASED AND LOCKED, exactly like FederatedWeights.merge, so this can run
while the server is playing games without either side losing the other's learning.
"""
import json, sys, math, os, fcntl, collections, re

FULL_TRUST_VERSION = 500
WEIGHTS = "/home/user/projects/mage-modern/ai-weights/commander-weights.txt"
# Must match OnlineTDLearner.SCALE, in StateFeatures.NAMES order.
SCALE = [40, 7, 8, 20, 20, 8, 8, 6, 4, 2, 1, 60, 30, 25, 4, 3]
NAMES = ["life_diff", "hand_size_diff", "creature_count_diff", "creature_power_diff",
         "creature_toughness_diff", "land_count_diff", "untapped_land_diff",
         "artifact_count_diff", "enchantment_count_diff", "planeswalker_count_diff",
         "commander_on_battlefield_diff", "library_size_diff", "turn_number",
         "deployed_mana_value_diff", "unspent_mana_own_turn", "draw_engine_count_diff"]


def load(paths):
    feats = collections.defaultdict(list)   # game -> [(player, features)]
    results = {}                            # game -> {player: won}
    for p in paths:
        with open(p, errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line.startswith("{"):
                    # log4j appends "  =>[GAME <id>] Class.method" AFTER the json, so slicing from
                    # the opening brace to end-of-line leaves trailing text and every parse fails
                    # silently -- which reads as "no training data" rather than as a bug.
                    m = re.search(r'\{"kind".*\}', line)
                    if not m:
                        continue
                    line = m.group(0)
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                if r.get("kind") == "FEATURES" and r.get("features"):
                    feats[r.get("game")].append((r.get("player"), r["features"]))
                elif r.get("kind") == "RESULT":
                    results[r.get("game")] = {s["name"]: (bool(s.get("won")), bool(s.get("human")))
                                              for s in r.get("seats", [])}
    return feats, results


# How much each kind of trajectory counts. A human WIN is the scarcest and most
# informative thing this system ever sees: a person who beat the bots demonstrated a line
# that self-play cannot manufacture, and there will only ever be a handful of them against
# thousands of simulated games. Without weighting, those rows would be statistically
# invisible -- 20 human games against 5000 self-play games is 0.4% of the gradient.
#
# Human LOSSES are weighted up too, but less. "A human lost from here" is real evidence,
# though a human losing to bots says more about that game than about good play.
WEIGHT_HUMAN_WIN = 20.0
WEIGHT_HUMAN_LOSS = 5.0
WEIGHT_BOT = 1.0


def sample_weight(won, human):
    if human:
        return WEIGHT_HUMAN_WIN if won else WEIGHT_HUMAN_LOSS
    return WEIGHT_BOT


def build(feats, results):
    """One training row per (seat, turn) snapshot, labelled with that seat's real result."""
    X, y, W = [], [], []
    used_games = 0
    stats = collections.Counter()
    for gid, rows in feats.items():
        outcome = results.get(gid)
        if not outcome:
            continue          # game never finished, or its RESULT is not in this file
        hit = False
        for player, f in rows:
            if player not in outcome or len(f) != len(NAMES):
                continue
            won, human = outcome[player]
            X.append(f)
            y.append(1.0 if won else 0.0)
            W.append(sample_weight(won, human))
            stats[("human" if human else "bot") + ("-win" if won else "-loss")] += 1
            hit = True
        used_games += hit
    return X, y, W, used_games, stats


def read_weights(path):
    w = [0.0] * len(NAMES)
    bias, version = 0.0, 0
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip()
            if k == "bias":
                bias = float(v)
            elif k == "version":
                version = int(float(v))
            elif k in NAMES:
                w[NAMES.index(k)] = float(v)
    return w, bias, version


def main():
    paths = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply_ = "--apply" in sys.argv
    epochs = 20
    for a in sys.argv[1:]:
        if a.startswith("--epochs="):
            epochs = int(a.split("=", 1)[1])
    if not paths:
        print(__doc__)
        return 1

    feats, results = load(paths)
    X, y, W, games, stats = build(feats, results)
    print(f"{len(feats)} games with features, {len(results)} with results")
    print(f"-> {len(X)} training rows from {games} finished games "
          f"({sum(y):.0f} winner rows, {len(y)-sum(y):.0f} loser rows)")
    if stats:
        print("   by source:", dict(stats))
        hw = stats.get("human-win", 0)
        if hw:
            share = 100.0 * hw * WEIGHT_HUMAN_WIN / sum(W)
            print(f"   human wins are {hw} rows but {share:.0f}% of the gradient "
                  f"(weighted {WEIGHT_HUMAN_WIN}x)")
    if len(X) < 50:
        print("\nNot enough data yet. Play or simulate more games -- FEATURES records only")
        print("appear for games played since feature snapshots were added.")
        return 0

    w, bias, version = read_weights(WEIGHTS)
    base_w, base_bias = list(w), bias
    lr = 0.05
    for ep in range(epochs):
        loss = 0.0
        for xi, yi, wi in zip(X, y, W):
            z = bias + sum(w[i] * xi[i] / SCALE[i] for i in range(len(w)))
            z = max(-30.0, min(30.0, z))
            p = 1.0 / (1.0 + math.exp(-z))
            err = (yi - p) * wi
            loss += wi * -(yi * math.log(max(p, 1e-9)) + (1 - yi) * math.log(max(1 - p, 1e-9)))
            for i in range(len(w)):
                w[i] += lr * err * xi[i] / SCALE[i]
            bias += lr * err
        if ep in (0, epochs - 1):
            print(f"  epoch {ep+1}: weighted log-loss {loss/sum(W):.4f}")

    acc = sum(1 for xi, yi in zip(X, y)
              if (1.0 / (1.0 + math.exp(-max(-30, min(30, bias + sum(w[i]*xi[i]/SCALE[i] for i in range(len(w))))))) >= 0.5) == (yi >= 0.5))
    print(f"  training accuracy: {100*acc/len(X):.1f}%  (50% = no signal)")

    print("\nlargest learned weights:")
    for name, val in sorted(zip(NAMES, w), key=lambda kv: -abs(kv[1]))[:6]:
        print(f"   {name:<32}{val:+.4f}")

    if not apply_:
        print("\n(dry run -- pass --apply to merge into the global model)")
        return 0

    # delta merge under the same file lock FederatedWeights uses
    with open(WEIGHTS, "a+") as fh:
        fcntl.flock(fh, fcntl.LOCK_EX)
        cur_w, cur_bias, cur_version = read_weights(WEIGHTS)
        merged = [cur_w[i] + (w[i] - base_w[i]) for i in range(len(w))]
        merged_bias = cur_bias + (bias - base_bias)
        new_version = cur_version + games
        with open(WEIGHTS + ".tmp", "w") as out:
            out.write("# commander bot federated weights -- written by tools/train-from-games.py\n")
            out.write(f"version={new_version}\n")
            out.write(f"bias={merged_bias}\n")
            for n, v in zip(NAMES, merged):
                out.write(f"{n}={v}\n")
        os.replace(WEIGHTS + ".tmp", WEIGHTS)
        fcntl.flock(fh, fcntl.LOCK_UN)
    print(f"\nmerged into global model: version {cur_version} -> {new_version} "
          f"(trust {min(1.0, new_version/FULL_TRUST_VERSION)*100:.0f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
