#!/usr/bin/env python3
"""Compare how two bots actually PLAYED, not just who won.

    tools/compare-bots.py <label> <audit-log> [<audit-log> ...]

A win rate says which bot won; it does not say what either did differently. This reads the
audit stream and contrasts the two seats on the behaviours we have specific evidence
matter in this fork:

  casts / lands       deployment -- the "sits doing nothing" complaint
  attacks vs declined aggression, and whether attacks were even legal to make
  idle passes         and how many held >=4 mana with >=3 cards, the reported pathology
  stuck rate          the same, as a share of that bot's idle passes
  mulligans           whether the London bottoming logic is running at all

Seats are identified by name: the bench calls them Seat1/Seat2 and BenchRunner alternates
which side each is, so pass the jsonl alongside if you need side attribution. Here the two
seats are simply reported separately, which is what a mirror match needs -- same deck both
sides, so any behavioural gap is the bots, not the decks.
"""
import json, sys, re, collections

WIN_SENTINEL = 50_000_000


def load(paths):
    recs = []
    for p in paths:
        try:
            fh = open(p, errors="replace")
        except FileNotFoundError:
            continue
        for line in fh:
            line = line.strip()
            if not line.startswith("{"):
                # log4j appends "  =>[GAME <id>] Class.method" AFTER the json, so slicing from
                # the opening brace to end-of-line leaves trailing text and every parse fails.
                # Match to the LAST closing brace instead.
                m = re.search(r'\{"kind".*\}', line)
                if not m:
                    continue
                line = m.group(0)
            try:
                recs.append(json.loads(line))
            except ValueError:
                continue
    return recs


def classify(detail):
    if detail is None:
        return "other"
    if detail.endswith("Pass"):
        return "pass"
    if ": Play " in detail:
        return "land"
    if ": Cast " in detail:
        return "cast"
    return "activate"


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    label, paths = sys.argv[1], sys.argv[2:]
    recs = load(paths)
    if not recs:
        print(f"{label}: no audit records found")
        return 0

    games = {r.get("game") for r in recs if r.get("game")}
    seats = sorted({r.get("player") for r in recs if r.get("player")})

    print(f"=== {label} ===")
    print(f"{len(recs)} audit records across {len(games)} games, seats: {', '.join(seats)}")

    rows = {}
    for seat in seats:
        mine = [r for r in recs if r.get("player") == seat]
        acts = collections.Counter(classify(r.get("detail"))
                                   for r in mine if r.get("kind") == "PLAY")
        idle = [r for r in mine if r.get("kind") == "IDLE"]
        stuck = [r for r in idle if r.get("mana", 0) >= 4 and r.get("hand", 0) >= 3]
        atk = [r for r in mine if r.get("kind") == "ATTACK"]
        noatk = [r for r in mine if r.get("kind") == "NO_ATTACK"]
        # a declined attack only matters if something COULD have attacked
        noatk_able = [r for r in noatk if r.get("available", 0) > 0]
        blk = [r for r in mine if r.get("kind") == "BLOCK"]
        noblk_able = [r for r in mine if r.get("kind") == "NO_BLOCK" and r.get("available", 0) > 0]
        mull = [r for r in mine if r.get("kind") == "MULLIGAN"]
        rows[seat] = {
            "casts": acts["cast"], "lands": acts["land"], "activations": acts["activate"],
            "attacks": len(atk), "declined (could)": len(noatk_able),
            "blocks": len(blk), "declined blocks (could)": len(noblk_able),
            "idle": len(idle), "stuck": len(stuck),
            "stuck %": round(100.0 * len(stuck) / len(idle), 1) if idle else 0.0,
            "mulligans": len(mull),
        }

    keys = list(next(iter(rows.values())).keys())
    width = max(len(k) for k in keys) + 2
    print(f"\n  {'metric':<{width}}" + "".join(f"{s:>16}" for s in seats) + "     delta")
    for k in keys:
        vals = [rows[s][k] for s in seats]
        delta = f"{vals[0]-vals[1]:+.1f}" if len(vals) == 2 else ""
        print(f"  {k:<{width}}" + "".join(f"{v:>16}" for v in vals) + f"{delta:>10}")

    # worst idle moments, which is where the reported pathology shows
    stuck_all = [r for r in recs if r.get("kind") == "IDLE"
                 and r.get("mana", 0) >= 4 and r.get("hand", 0) >= 3]
    if stuck_all:
        worst = sorted(stuck_all, key=lambda r: -r.get("mana", 0))[:4]
        print("\n  worst idle passes (mana held with a full hand):")
        for r in worst:
            print(f"     {r.get('player'):<8} T{r.get('turn'):<3} hand={r.get('hand')} "
                  f"mana={r.get('mana')} score={r.get('score')}")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
