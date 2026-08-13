#!/usr/bin/env python3
"""Fold isolated worker models back into the one global model, so no learning is discarded.

    tools/merge-worker-models.py <global> <worker-model> [<worker-model> ...] [--apply]

WHY THIS EXISTS

A measurement run points each worker at its OWN copy of the model, because a model that
updates while it is being measured makes the number describe a moving target. That is
right for measuring and wrong for everything else: when the run ends those copies are
discarded, and every game they played is thrown away.

This recovers them. Each worker's contribution is its DELTA from the shared baseline it
started at -- not its absolute weights -- so folding several workers in is the same FedAvg
the live merge does, and one worker cannot stomp another's learning by having finished
later. Absolute weights would let the last file win outright.

Deltas are averaged, not summed. Four workers that each learned "creature power matters
slightly more" should move the global model by about that much, not by four times it.

The version counter advances by the total games contributed, which keeps it meaning what
it means everywhere else: the number of games that have shaped this model.
"""
import sys, os, fcntl

NAMES = ["life_diff", "hand_size_diff", "creature_count_diff", "creature_power_diff",
         "creature_toughness_diff", "land_count_diff", "untapped_land_diff",
         "artifact_count_diff", "enchantment_count_diff", "planeswalker_count_diff",
         "commander_on_battlefield_diff", "library_size_diff", "turn_number",
         "deployed_mana_value_diff", "unspent_mana_own_turn", "draw_engine_count_diff"]


def read(path):
    w = {n: 0.0 for n in NAMES}
    bias, version = 0.0, 0
    if not os.path.exists(path):
        return w, bias, version
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        try:
            fv = float(v)
        except ValueError:
            continue
        if k == "bias":
            bias = fv
        elif k == "version":
            version = int(fv)
        elif k in w:
            w[k] = fv
    return w, bias, version


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply_ = "--apply" in sys.argv
    if len(args) < 2:
        print(__doc__)
        return 1
    global_path, worker_paths = args[0], args[1:]

    gw, gbias, gversion = read(global_path)
    print(f"global model: {global_path}  version {gversion}")

    deltas, total_games = [], 0
    for p in worker_paths:
        ww, wbias, wversion = read(p)
        games = wversion - gversion
        if games <= 0:
            print(f"  {os.path.basename(p):<28} version {wversion} -- no new games, skipping")
            continue
        total_games += games
        deltas.append(({n: ww[n] - gw[n] for n in NAMES}, wbias - gbias, games))
        print(f"  {os.path.basename(p):<28} version {wversion}  (+{games} games)")

    if not deltas:
        print("\nnothing to merge")
        return 0

    # average the deltas: four workers each learning the same lesson should move the model
    # once, not four times
    merged = {n: gw[n] + sum(d[0][n] for d in deltas) / len(deltas) for n in NAMES}
    merged_bias = gbias + sum(d[1] for d in deltas) / len(deltas)

    print(f"\nlargest changes from folding {len(deltas)} worker model(s) back in:")
    for n in sorted(NAMES, key=lambda n: -abs(merged[n] - gw[n]))[:6]:
        print(f"   {n:<32}{gw[n]:+.4f} -> {merged[n]:+.4f}   ({merged[n]-gw[n]:+.4f})")

    if not apply_:
        print("\n(dry run -- pass --apply to write it)")
        return 0

    with open(global_path, "a+") as fh:
        fcntl.flock(fh, fcntl.LOCK_EX)
        cur_w, cur_bias, cur_version = read(global_path)
        # re-derive against whatever the global model is NOW, in case it moved while we read
        final = {n: cur_w[n] + (merged[n] - gw[n]) for n in NAMES}
        final_bias = cur_bias + (merged_bias - gbias)
        tmp = global_path + ".tmp"
        with open(tmp, "w") as out:
            out.write("# commander bot federated weights -- written by tools/merge-worker-models.py\n")
            out.write(f"version={cur_version + total_games}\n")
            out.write(f"bias={final_bias}\n")
            for n in NAMES:
                out.write(f"{n}={final[n]}\n")
        os.replace(tmp, global_path)
        fcntl.flock(fh, fcntl.LOCK_UN)
    print(f"\nmerged: version {cur_version} -> {cur_version + total_games} "
          f"({total_games} games recovered)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
