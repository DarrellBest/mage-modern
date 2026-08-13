#!/usr/bin/env bash
# Full deploy (build + jar swap + restart), but only once the server has been EMPTY
# for a sustained period. Same race-free discipline as restart-when-idle.sh: N
# consecutive clear readings plus a final re-check immediately before acting.
#
# Use this rather than restart-when-idle.sh whenever code changed -- a plain restart
# reloads config but keeps the old jars, which silently applies half the intended change.
set -uo pipefail
cd "$(dirname "$0")/.."
QUIET_CHECKS=${QUIET_CHECKS:-10}
INTERVAL=${INTERVAL:-30}
MAX_WAIT=${MAX_WAIT:-86400}
conns() { bash "$(dirname "$0")/active-games.sh" >/dev/null 2>&1 && bash "$(dirname "$0")/active-games.sh" | head -1 || echo 0; }
clear_count=0; waited=0
while [ "$waited" -lt "$MAX_WAIT" ]; do
  c=$(conns)
  if [ "$c" -eq 0 ]; then clear_count=$((clear_count+1))
  else
    [ "$clear_count" -gt 0 ] && echo "$(date +%H:%M:%S) a game started ($c) - resetting"
    clear_count=0
  fi
  if [ "$clear_count" -ge "$QUIET_CHECKS" ]; then
    if [ "$(conns)" -ne 0 ]; then
      echo "$(date +%H:%M:%S) a game started at the last moment - waiting"; clear_count=0; continue
    fi
    echo "$(date +%H:%M:%S) idle for $((QUIET_CHECKS*INTERVAL))s - deploying"
    bash tools/deploy-fork.sh && echo "$(date +%H:%M:%S) deploy done"
    for _ in $(seq 60); do
      ss -ltn 2>/dev/null | grep -qE ':17171' && { echo "$(date +%H:%M:%S) listening again"; exit 0; }
      sleep 2
    done
    echo "WARNING: :17171 not listening after deploy"; exit 1
  fi
  sleep "$INTERVAL"; waited=$((waited+INTERVAL))
done
echo "gave up waiting; changes staged but NOT live"; exit 2
