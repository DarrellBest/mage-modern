#!/usr/bin/env bash
# Restart xmage-fork only after the server has been EMPTY for a sustained period.
#
# A config change needs a restart, and a restart drops every player mid-game. Waiting
# for a single zero reading is not enough -- someone can connect in the gap between the
# check and the restart, which is how a four-player game died at 17:01 today. This
# requires N consecutive clear readings and re-checks immediately before acting.
set -uo pipefail
QUIET_CHECKS=${QUIET_CHECKS:-10}     # consecutive clear readings required
INTERVAL=${INTERVAL:-30}             # seconds between readings
MAX_WAIT=${MAX_WAIT:-86400}

conns() { bash "$(dirname "$0")/active-games.sh" >/dev/null 2>&1 && bash "$(dirname "$0")/active-games.sh" | head -1 || echo 0; }

clear_count=0
waited=0
while [ "$waited" -lt "$MAX_WAIT" ]; do
  c=$(conns)
  if [ "$c" -eq 0 ]; then
    clear_count=$((clear_count+1))
  else
    [ "$clear_count" -gt 0 ] && echo "$(date +%H:%M:%S) a game started ($c) - resetting quiet counter"
    clear_count=0
  fi
  if [ "$clear_count" -ge "$QUIET_CHECKS" ]; then
    if [ "$(conns)" -ne 0 ]; then      # final re-check, closes the race
      echo "$(date +%H:%M:%S) a game started at the last moment - continuing to wait"
      clear_count=0
      continue
    fi
    echo "$(date +%H:%M:%S) server idle for $((QUIET_CHECKS*INTERVAL))s - restarting"
    sudo systemctl restart xmage-fork && echo "restarted"
    for _ in $(seq 60); do
      ss -ltn 2>/dev/null | grep -qE ':17171' && { echo "$(date +%H:%M:%S) listening again"; exit 0; }
      sleep 2
    done
    echo "WARNING: did not observe :17171 listening after restart"; exit 1
  fi
  sleep "$INTERVAL"; waited=$((waited+INTERVAL))
done
echo "gave up waiting after ${MAX_WAIT}s; config change is staged but NOT active"; exit 2
