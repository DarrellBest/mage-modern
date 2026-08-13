#!/usr/bin/env python3
"""Turn the AI audit stream into a per-game report: who won, and what each bot did.

    tools/game-report.py [ai-audit.jsonl ...]           # summary of every game
    tools/game-report.py --game 4abc894e ai-audit.jsonl # full play-by-play for one game
    tools/game-report.py --plays --game <id> <file>     # every decision, in order

Reads the JSONL written by the `mage.ai.audit` logger. Records also land in the ordinary
server log when that logger has no appender of its own, so this accepts mixed text too and
picks out anything that looks like an audit record.

Why this exists: every hand-rolled attempt to answer "how did the bot do" got something
wrong. The phase name contains a space, so naive patterns matched nothing. Players are
"Seat1" on the bench and "Computer 3" live, so bench-shaped patterns found zero rows. And
the server runs several tables at once, so slicing by timestamp interleaved a turn-1 game
with a turn-32 game and credited one bot's win to another. Every record here carries its
game id, and this script always groups by it.
"""
import json, sys, collections, re

WIN_SENTINEL = 50_000_000  # evaluator returns +/-100000000 for a decided game


def load(paths):
    recs = []
    for p in paths:
        with open(p, errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                if not line.startswith("{"):
                    m = re.search(r'\{"kind".*\}', line)   # embedded in a text log line
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
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only = None
    plays = "--plays" in sys.argv
    if "--game" in sys.argv:
        only = sys.argv[sys.argv.index("--game") + 1]
        args = [a for a in args if a != only]
    if not args:
        print(__doc__)
        return 1

    recs = load(args)
    if not recs:
        print("no audit records found")
        return 1

    games = collections.defaultdict(list)
    for r in recs:
        games[r.get("game", "?")].append(r)

    print(f"{len(recs)} records across {len(games)} games\n")

    for gid, rs in sorted(games.items(), key=lambda kv: -len(kv[1])):
        if only and not gid.startswith(only):
            continue
        result = next((r for r in rs if r.get("kind") == "RESULT"), None)
        turns = max((r.get("turn", 0) for r in rs), default=0)
        bots = sorted({r.get("player", "?") for r in rs if r.get("kind") != "RESULT"})

        # winner: the RESULT record if present, else the evaluator sentinel
        if result:
            won = [s["name"] for s in result.get("seats", []) if s.get("won")]
            winner = ", ".join(won) or result.get("winner") or "?"
            lives = "  ".join(f"{s['name']}={s.get('life','?')}" for s in result.get("seats", []))
        else:
            sent = sorted({r["player"] for r in rs if r.get("score", 0) >= WIN_SENTINEL})
            winner = (", ".join(sent) + "  (inferred from score sentinel)") if sent else "unknown"
            lives = ""

        print(f"=== game {gid[:8]}  turns {turns}  winner: {winner}")
        if lives:
            print(f"    final life: {lives}")

        for bot in bots:
            mine = [r for r in rs if r.get("player") == bot]
            acts = collections.Counter(classify(r.get("detail")) for r in mine if r.get("kind") == "PLAY")
            idle = [r for r in mine if r.get("kind") == "IDLE"]
            stuck = [r for r in idle if r.get("mana", 0) >= 4 and r.get("hand", 0) >= 3]
            atk = [r for r in mine if r.get("kind") == "ATTACK"]
            noatk = [r for r in mine if r.get("kind") == "NO_ATTACK"]
            mull = [r for r in mine if r.get("kind") == "MULLIGAN"]
            print(f"    {bot:<14} casts {acts['cast']:>3}  lands {acts['land']:>3}  "
                  f"activations {acts['activate']:>3}  attacks {len(atk):>3} (declined {len(noatk)})  "
                  f"idle {len(idle):>3} (stuck {len(stuck)})"
                  + (f"  mulligans {len(mull)}" if mull else ""))
            if stuck:
                worst = max(stuck, key=lambda r: r.get("mana", 0))
                print(f"        worst idle: T{worst['turn']} hand={worst['hand']} mana={worst['mana']}")

        if plays and only:
            print("\n    --- play by play ---")
            for r in rs:
                if r.get("kind") == "RESULT":
                    continue
                d = r.get("detail") or ""
                extra = ""
                if r.get("kind") == "IDLE":
                    extra = f"hand={r.get('hand')} mana={r.get('mana')}"
                print(f"    T{r.get('turn'):>3} {r.get('phase',''):<16} {r.get('player',''):<12} "
                      f"{r.get('kind'):<9} {d[:70]} {extra}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
