#!/usr/bin/env bash
# Deploy ONLY when nobody is connected. A deploy restarts xmage-fork and drops every
# player mid-game.
#
# This exists because a check that merely PRINTS the connection count is not a guard.
# On 2026-08-12 at 17:01 a deploy ran with 14 established connections and killed a
# four-player commander game that had started ten seconds earlier. The count had been
# printed in the same command as the deploy, so it scrolled past and gated nothing.
set -euo pipefail
cd "$(dirname "$0")/.."

conns=$(bash "$(dirname "$0")/active-games.sh" | head -1)
if [ "${FORCE:-0}" != "1" ] && [ "$conns" -gt 0 ]; then
  echo "REFUSING TO DEPLOY: $conns game(s) in progress."
  bash "$(dirname "$0")/active-games.sh" | tail -n +2
  echo
  echo "A game is in progress and a deploy restarts the server mid-game."
  echo "Re-run when it is clear, or FORCE=1 $0 to override deliberately."
  exit 1
fi
echo ">> no games in progress; clear to deploy"
exec bash tools/deploy-fork.sh "$@"
