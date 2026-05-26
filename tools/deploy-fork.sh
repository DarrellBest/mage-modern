#!/usr/bin/env bash
#
# deploy-fork.sh — one command to ship a new fork build end-to-end.
#
# Run from the repo root on the dev box (which has Maven + JDK). Java jars are
# platform-independent, so we build here (where git lives) and ship only the
# rebuilt fork jars to the server.
#
#   ./tools/deploy-fork.sh
#
# What it does:
#   1. Full clean build (skipTests) -> fresh mage-*-<version>.jar for every module.
#   2. Stage just the fork jars (only this build produces *-<version>.jar).
#   3. rsync them to the server.
#   4. On the server, swap those jars into EVERY place fork jars live:
#        - the download bundle   (mage-client/{lib,plugins}, mage-server/{lib,plugins})
#        - the LIVE server        (~/Documents/xmage/.../mage-server/{lib,plugins})
#        - the local test client  (~/Documents/xmage/.../mage-client/lib)
#      then re-zip the download bundle, write a fresh unique version string into
#      config.json (so every launcher detects an update), and RESTART the live
#      fork server so play.darrellbest.com:17171 runs the new code immediately.
#
# The live server runs as the `xmage-fork` systemd service (auto-start on boot,
# auto-restart on crash). Its start wrapper picks whatever mage-server-*.jar is
# present, so version bumps need no edits. `user` has passwordless sudo for
# `systemctl {start,stop,restart,status} xmage-fork` (see /etc/sudoers.d/xmage-fork).
#
set -euo pipefail

REMOTE=user@192.168.1.87
STAGE=/tmp/forkjars

cd "$(dirname "$0")/.."                  # repo root
POMV=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
echo ">> Building fork $POMV"

# 1. full clean build (portable jars)
MAVEN_OPTS="-Xmx3g" mvn -q clean install -DskipTests -T 1C -Dmaven.javadoc.skip=true

# 2. stage fresh fork jars (1.4.NN is brand-new this build, so this matches only ours)
rm -rf "$STAGE"; mkdir -p "$STAGE"
find ~/.m2/repository -name "mage*-${POMV}.jar" \
  ! -name '*-sources.jar' ! -name '*-tests.jar' -exec cp {} "$STAGE/" \;
echo ">> staged $(ls "$STAGE" | wc -l) fork jars"

# 3. ship to server
rsync -az --delete "$STAGE/" "$REMOTE:$STAGE/"

# 4. on the server: refresh bundle + live server, re-zip, bump version, restart server
#    (POMV passed as $1 so the spaced version string is built remotely, not via ssh args)
ssh "$REMOTE" 'bash -s' "$POMV" <<'REMOTE_EOF'
set -euo pipefail
POMV="$1"
VERSTR="${POMV}-fork ($(date '+%Y-%m-%d %H-%M'))"   # unique per build -> forces update
STAGE=/tmp/forkjars
BUNDLE=/home/user/dist/bundle                       # assembled download distribution
LIVE=/home/user/Documents/xmage/xmage               # the running install (server has db/saved/config)
WEBZIP=/var/www/html/files/mage-update_fork.zip
CONFIG=/var/www/html/config.json

# replace every mage*-<oldver>.jar in a dir with the staged mage*-<POMV>.jar
swap_dir() { local dir="$1"; [ -d "$dir" ] || return 0; local n=0
  for old in "$dir"/mage*-*.jar; do [ -e "$old" ] || continue
    base=$(basename "$old" | sed -E 's/-[0-9].*\.jar$//'); new="$STAGE/${base}-${POMV}.jar"
    if [ -f "$new" ]; then rm -f "$old"; cp "$new" "$dir/"; n=$((n+1)); fi   # guard: only our modules
  done; echo "   swapped $n -> $dir"; }

echo ">> refreshing download bundle"
swap_dir "$BUNDLE/mage-client/lib";  swap_dir "$BUNDLE/mage-client/plugins"
swap_dir "$BUNDLE/mage-server/lib";  swap_dir "$BUNDLE/mage-server/plugins"
echo ">> refreshing LIVE server (+ local test client)"
swap_dir "$LIVE/mage-server/lib";    swap_dir "$LIVE/mage-server/plugins"
swap_dir "$LIVE/mage-client/lib"
stale=$(find "$BUNDLE" "$LIVE" -name '*-[0-9]*.jar' ! -name "*-${POMV}.jar" -path '*mage*' | grep -E '/mage[^/]*-[0-9]' | wc -l || true)

echo ">> re-zipping bundle"
cd "$BUNDLE"; rm -f "$WEBZIP"; zip -qr "$WEBZIP" mage-client mage-server
echo "   bundle: $(du -h "$WEBZIP" | cut -f1)"

echo ">> bumping config version"
python3 - "$CONFIG" "$VERSTR" <<'PY'
import json, sys
path, ver = sys.argv[1], sys.argv[2]
c = json.load(open(path)); c["XMage"]["version"] = ver
json.dump(c, open(path, "w"), indent=2); print("   XMage.version ->", ver)
PY

echo ">> restarting live fork server (xmage-fork systemd service)"
sudo systemctl restart xmage-fork
echo ">> deployed $VERSTR ; server restarting (loads ~28k cards, ~40s to listen on :17171)"
REMOTE_EOF
echo ">> DONE."
