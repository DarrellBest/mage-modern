#!/usr/bin/env bash
# Is a real game in progress? Prints the count on stdout, then any game ids.
#
# ALWAYS exits 0. It previously exited 1 for "no games", which combined with the callers'
# `set -euo pipefail` to kill the deploy script at the assignment -- so the gate silently
# skipped the deploy in exactly the case where it was supposed to allow it, with no output
# and a success exit. Callers branch on the COUNT, never on the exit status.
#
# TCP connections are the WRONG signal: a client parked in the lobby holds sockets open
# indefinitely, so a connection-based gate blocks deploys forever on people who are AFK.
# What actually matters is whether a game would be interrupted.
#
# Signal: log lines emitted from a game thread, which log4j names "GAME <uuid>". Any activity
# there -- AI decisions, triggers, priority passes -- means a game is live. A human-vs-human
# game with both players thinking still emits nothing, so WINDOW is deliberately generous.
set -uo pipefail
LOG=${LOG:-/home/user/Documents/xmage/xmage/mage-server/mageserver.log}
WINDOW=${WINDOW:-300}   # seconds of quiet before a game counts as over

[ -f "$LOG" ] || { echo "0"; exit 0; }

now=$(date +%s)
cutoff=$(date -d "@$((now - WINDOW))" '+%Y-%m-%d %H:%M:%S')

# last timestamp seen per game thread, from the tail of the log (cheap on a 190MB file)
active=$(tail -c 4000000 "$LOG" 2>/dev/null \
  | grep -oE '^[A-Z]+ +([0-9-]{10} [0-9:]{8}),[0-9]+ .*=>\[GAME ([0-9a-f-]+)\]' \
  | sed -E 's/^[A-Z]+ +([0-9-]{10} [0-9:]{8}),[0-9]+ .*=>\[GAME ([0-9a-f-]+)\]/\2 \1/' \
  | awk '{ t[$1]=$2" "$3 } END { for (g in t) print g, t[g] }' \
  | awk -v c="$cutoff" '$2" "$3 >= c { print $1 }')

n=$(printf '%s' "$active" | grep -c . || true)
echo "$n"
if [ "$n" -gt 0 ]; then
  printf '%s\n' "$active" | sed 's/^/  live game: /'
fi
exit 0
