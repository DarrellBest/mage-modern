#!/usr/bin/env bash
#
# full-deploy.sh — ONE command: sync upstream -> merge -> build -> ship -> restart -> VERIFY.
#
#   ./tools/full-deploy.sh
#
# Wraps the proven deploy-fork.sh (build + ship jars + re-zip bundle + bump config +
# restart) with the steps this project learned the hard way:
#
#   * Stage 1  Upstream sync   — fetch magefree/mage, fast-forward master, merge into
#                                the work branch, push both. Aborts cleanly on conflicts.
#   * Stage 2  Build + deploy  — deploy-fork.sh (unchanged, proven core).
#   * Stage 3  Restart verify  — confirm the LIVE process is actually running the new
#                                mage-server-<ver>.jar AND listening on :17171. (We were
#                                burned assuming "the script restarted it" — always verify.)
#   * Stage 4  Heap guard      — warn loudly if the server heap is small. A 1 GB heap
#                                OOM'd ("GC overhead limit exceeded") on AI Commander games;
#                                it lives in mage-server/start-fork.sh, now -Xmx8192m.
#   * Stage 5  Version audit   — no STALE version-stamped mage jars anywhere (live server,
#                                live client, download bundle, update zip). Duplicate
#                                old+new jars on a lib/* classpath caused "wrong client
#                                version" handshake failures.
#
# NOTE: the Electron launcher is a SEPARATE artifact (built on the x86_64 server via
# electron-builder) and is intentionally NOT part of this script — it only changes when
# the launcher's own code changes, not on every game deploy.
#
set -euo pipefail

cd "$(dirname "$0")/.."                      # repo root
REPO_DIR="$(pwd)"
REMOTE=user@192.168.1.87
WORK_BRANCH=ui-modernization                 # our fork's feature branch (what gets deployed)
MAIN_BRANCH=master                           # upstream's default branch
MIN_HEAP_MB=4096                             # warn below this (OOM lesson)

say(){ printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die(){ printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- Stage 1: upstream sync
say "[1/5] Sync upstream (magefree/mage) into the fork"
[ -z "$(git status --porcelain)" ] || { git status -s; die "working tree not clean — commit/stash first"; }
git fetch upstream --no-tags
behind=$(git rev-list --count ${MAIN_BRANCH}..upstream/${MAIN_BRANCH})
echo "    ${MAIN_BRANCH} is $behind commit(s) behind upstream/${MAIN_BRANCH}"
git checkout "$MAIN_BRANCH"
git merge --ff-only "upstream/${MAIN_BRANCH}"
git push origin "$MAIN_BRANCH"
git checkout "$WORK_BRANCH"
if ! git merge --no-edit "$MAIN_BRANCH"; then
  echo "    conflicts:"; git diff --name-only --diff-filter=U | sed 's/^/      /'
  git merge --abort
  die "merge conflicts merging ${MAIN_BRANCH} into ${WORK_BRANCH} — resolve manually, then re-run"
fi
git push origin "$WORK_BRANCH"

POMV=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
echo "    deploying version: $POMV  (commit $(git rev-parse --short HEAD))"

# ---------------------------------------------------------------- Stage 2: build + deploy
say "[2/5] Build + ship + restart (deploy-fork.sh)"
"$REPO_DIR/tools/deploy-fork.sh"

# ---------------------------------------------------------------- Stages 3-5: remote verify
say "[3/5..5/5] Verify on $REMOTE (restart + heap + version audit)"
ssh -o BatchMode=yes "$REMOTE" 'bash -s' "$POMV" "$MIN_HEAP_MB" <<'REMOTE_EOF'
set -uo pipefail
POMV="$1"; MIN_HEAP_MB="$2"
LIVE=/home/user/Documents/xmage/xmage
BUNDLE=/home/user/dist/bundle
WEBZIP=/var/www/html/files/mage-update_fork.zip
CONFIG=/var/www/html/config.json
RC=0

echo "--- [3] restart verification ---"
# wait for the game port (server reloads ~28k cards on boot)
for i in $(seq 1 40); do ss -tln 2>/dev/null | grep -q ":17171" && break; sleep 3; done
proc=$(ps -eo etimes,args | grep "[j]ava .*mage-server-.*\.jar" | head -1)
[ -n "$proc" ] || { echo "  FAIL: no mage-server process running"; RC=1; }
jar=$(echo "$proc" | grep -oE 'mage-server-[0-9.]+\.jar')
heap=$(echo "$proc" | grep -oE 'Xmx[0-9]+m'); up=$(echo "$proc" | awk '{print $1}')
echo "  running: ${jar:-none}  heap=${heap:-?}  up=${up:-?}s"
echo "$jar" | grep -q "mage-server-${POMV}.jar" || { echo "  FAIL: live jar != ${POMV}"; RC=1; }
ss -tln 2>/dev/null | grep -q ":17171" && echo "  :17171 LISTENING ✓" || { echo "  FAIL: :17171 not listening"; RC=1; }
ss -tln 2>/dev/null | grep -q ":17080" && echo "  :17080 web up ✓"      || echo "  WARN: web :17080 down"

echo "--- [4] heap guard (OOM lesson) ---"
heap_mb=$(echo "${heap:-Xmx0m}" | grep -oE '[0-9]+')
if [ "${heap_mb:-0}" -lt "$MIN_HEAP_MB" ]; then
  echo "  WARN: heap ${heap_mb}m < ${MIN_HEAP_MB}m — risk of OOM on AI Commander games (see start-fork.sh)"
else
  echo "  heap ${heap_mb}m >= ${MIN_HEAP_MB}m ✓"
fi

echo "--- [5] version audit: no stale jars (must all be ${POMV}) ---"
audit(){ local label="$1"; shift
  local vers; vers=$(ls "$@" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ *$//')
  if [ -z "$vers" ]; then echo "  $label: (no jars found)";
  elif [ "$vers" = "$POMV" ]; then echo "  $label: clean ($POMV) ✓"
  else echo "  $label: STALE/MIXED -> [$vers]"; RC=1; fi; }
audit "live server lib" "$LIVE"/mage-server/lib/mage-*.jar
audit "live client lib" "$LIVE"/mage-client/lib/mage-*.jar
audit "bundle server"   "$BUNDLE"/mage-server/lib/mage-*.jar
audit "bundle client"   "$BUNDLE"/mage-client/lib/mage-*.jar
zipvers=$(unzip -l "$WEBZIP" 2>/dev/null | grep -oE 'mage-(client|server)/lib/mage-[a-z]*-[0-9.]+\.jar' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u | tr '\n' ' ' | sed 's/ *$//')
if [ "$zipvers" = "$POMV" ]; then echo "  update zip: clean ($POMV) ✓"; else echo "  update zip: STALE/MIXED -> [$zipvers]"; RC=1; fi
echo "  config.json advertises: $(grep -A1 '"XMage"' "$CONFIG" | grep version | tr -d ' ')"

echo ""
[ "$RC" = 0 ] && echo "VERIFY: ALL GREEN ✓" || echo "VERIFY: FAILURES ABOVE ✗"
exit $RC
REMOTE_EOF

say "DONE — $POMV deployed, restarted, and verified."
