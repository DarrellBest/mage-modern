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

conns=$(ss -tn 2>/dev/null | grep -cE ':17171|:17172' || true)
if [ "${FORCE:-0}" != "1" ] && [ "$conns" -gt 0 ]; then
  echo "REFUSING TO DEPLOY: $conns connection(s) on the XMage ports."
  ss -tn 2>/dev/null | grep -E ':17171|:17172' | awk '{print "   " $1, $5}' | head
  echo
  echo "Someone is connected and a deploy restarts the server mid-game."
  echo "Re-run when it is clear, or FORCE=1 $0 to override deliberately."
  exit 1
fi
echo ">> $conns connections; clear to deploy"
exec bash tools/deploy-fork.sh "$@"
