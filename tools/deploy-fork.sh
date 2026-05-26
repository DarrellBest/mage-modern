#!/usr/bin/env bash
#
# deploy-fork.sh — build the fork, refresh the download bundle on the server,
#                  and bump the launcher version so clients see an update.
#
# Run from the repo root on the dev box (which has Maven + JDK). Java jars are
# platform-independent, so we build here (where git lives) and ship only the
# rebuilt fork jars to the server.
#
#   ./tools/deploy-fork.sh
#
# What it does:
#   1. Full clean build (skipTests) → fresh mage-*-<version>.jar for every module.
#   2. Stage just the fork jars (only this build produces *-<version>.jar).
#   3. rsync them to the server.
#   4. On the server: swap the fork jars into the download bundle (per dir, so
#      client/server keep their exact jar sets), re-zip, and write a fresh,
#      unique version string into config.json so every client sees "update".
#
set -euo pipefail

REMOTE=user@192.168.1.87
BUNDLE=/home/user/dist/bundle           # assembled distribution on the server
WEBZIP=/var/www/html/files/mage-update_fork.zip
CONFIG=/var/www/html/config.json
STAGE=/tmp/forkjars

cd "$(dirname "$0")/.."                  # repo root

POMV=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
VERSTR="${POMV}-fork ($(date '+%Y-%m-%d %H-%M'))"   # unique per build → forces update
echo ">> Building fork $POMV   version label: $VERSTR"

# 1. full clean build (portable jars)
MAVEN_OPTS="-Xmx3g" mvn -q clean install -DskipTests -T 1C -Dmaven.javadoc.skip=true

# 2. stage fresh fork jars (1.4.NN is brand-new this build, so this matches only ours)
rm -rf "$STAGE"; mkdir -p "$STAGE"
find ~/.m2/repository -name "mage*-${POMV}.jar" \
  ! -name '*-sources.jar' ! -name '*-tests.jar' -exec cp {} "$STAGE/" \;
echo ">> staged $(ls "$STAGE" | wc -l) fork jars"

# 3. ship to server
rsync -az --delete "$STAGE/" "$REMOTE:$STAGE/"

# 4. on the server: refresh bundle, re-zip, bump config version
ssh "$REMOTE" \
  POMV="$POMV" VERSTR="$VERSTR" BUNDLE="$BUNDLE" WEBZIP="$WEBZIP" CONFIG="$CONFIG" STAGE="$STAGE" \
  'bash -s' <<'REMOTE_EOF'
set -euo pipefail
for dir in "$BUNDLE/mage-client/lib" "$BUNDLE/mage-server/lib"; do
  for old in "$dir"/mage*-*.jar; do
    [ -e "$old" ] || continue
    base=$(basename "$old" | sed -E 's/-[0-9].*\.jar$//')   # mage-sets-1.4.58.jar -> mage-sets
    new="$STAGE/${base}-${POMV}.jar"
    if [ -f "$new" ]; then rm -f "$old"; cp "$new" "$dir/"; fi   # guard: only swap our modules
  done
done
cd "$BUNDLE"
rm -f "$WEBZIP"
zip -qr "$WEBZIP" mage-client mage-server
echo ">> bundle re-zipped: $(du -h "$WEBZIP" | cut -f1)"
python3 - "$CONFIG" "$VERSTR" <<'PY'
import json, sys
path, ver = sys.argv[1], sys.argv[2]
c = json.load(open(path))
c["XMage"]["version"] = ver
json.dump(c, open(path, "w"), indent=2)
print(">> config.json XMage.version ->", ver)
PY
REMOTE_EOF
echo ">> DONE. Clients will now be prompted to update to: $VERSTR"
