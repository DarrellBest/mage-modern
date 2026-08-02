#!/usr/bin/env bash
#
# deploy-fork.sh — one command to ship a new fork build end-to-end.
#
# Dual-home: run from the repo root on the game server itself (preferred — the build
# lands directly where it's served, no network hops) OR on a remote dev box (jars are
# platform-independent, so any box with Maven + a real JDK works; they ship via rsync).
# The script detects which case it's in by whether the live install dir exists locally.
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
LIVE_ROOT=/home/user/Documents/xmage/xmage     # exists only on the game server
export PATH="$HOME/.local/bin:$PATH"           # mvn lives in ~/.local/bin on both boxes

[ -d "$LIVE_ROOT" ] && ON_SERVER=1 || ON_SERVER=0
run_on_server(){ if [ "$ON_SERVER" = 1 ]; then bash -s "$@"; else ssh "$REMOTE" 'bash -s' "$@"; fi; }

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

# 2b. stage third-party deps the new manifests reference. start-fork.sh launches via
# `java -jar`, so the classpath is the manifest Class-Path with VERSION-PINNED names —
# when upstream bumps a dep (sqlite-jdbc 3.32->3.53, 2026-08-02), the new jar must ship
# too or the server dies at boot ("No suitable driver").
python3 - "$STAGE" <<'PY'
import os, re, sys, zipfile, glob, shutil
stage = sys.argv[1]
m2 = os.path.expanduser("~/.m2/repository")
jars = {}
for root, _, files in os.walk(m2):
    for f in files:
        if f.endswith(".jar") and not f.endswith(("-sources.jar", "-tests.jar")):
            jars.setdefault(f, os.path.join(root, f))
def stage_deps(mainglob):
    for main in glob.glob(os.path.join(stage, mainglob)):
        with zipfile.ZipFile(main) as z:
            mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        m = re.search(r"^Class-Path: (.*)$", mf.replace("\r\n", "\n").replace("\n ", ""), re.M)
        for e in (m.group(1).split() if m else []):
            b = os.path.basename(e)
            if b.endswith(".jar") and not os.path.exists(os.path.join(stage, b)) and b in jars:
                shutil.copy2(jars[b], os.path.join(stage, b))
stage_deps("mage-server-*.jar"); stage_deps("mage-client-*.jar")
print(">> staged manifest deps; stage total:", len(os.listdir(stage)))
PY

# 3. ship to server (no-op when building on the server itself)
[ "$ON_SERVER" = 1 ] || rsync -az --delete "$STAGE/" "$REMOTE:$STAGE/"

# 4. on the server: refresh bundle + live server, re-zip, bump version, restart server
#    (POMV passed as $1 so the spaced version string is built remotely, not via ssh args)
run_on_server "$POMV" <<'REMOTE_EOF'
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

# CRITICAL: config.xml registers plugins by version-stamped jar filename. After swapping
# jars to a new version, realign those refs or the player-type/deck-validator/game-type
# plugins fail to load ("Unknown player type", DeckValidatorFactory NPE) and games break.
echo ">> realigning config.xml plugin jar versions to ${POMV}"
for cfg in "$BUNDLE/mage-server/config/config.xml" "$LIVE/mage-server/config/config.xml"; do
  [ -f "$cfg" ] && sed -i -E "s/-[0-9]+\.[0-9]+\.[0-9]+\.jar/-${POMV}.jar/g" "$cfg" && echo "   updated $cfg"
done

# jar dirs must satisfy the main jar's manifest Class-Path (java -jar launch): add
# newly-referenced dep versions from the stage, drop the stale ones they replace.
echo ">> reconciling dependency jars against manifests"
python3 - "$STAGE" "$BUNDLE" "$LIVE" <<'PY'
import os, re, sys, zipfile, glob, shutil
stage, bundle, live = sys.argv[1:4]
def mf_entries(jar):
    with zipfile.ZipFile(jar) as z:
        mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    m = re.search(r"^Class-Path: (.*)$", mf.replace("\r\n", "\n").replace("\n ", ""), re.M)
    return [os.path.basename(e) for e in (m.group(1).split() if m else []) if e.endswith(".jar")]
rc = 0
for libdir, mainpat in [(bundle + "/mage-server/lib", "mage-server-*.jar"),
                        (bundle + "/mage-client/lib", "mage-client-*.jar"),
                        (live + "/mage-server/lib", "mage-server-*.jar"),
                        (live + "/mage-client/lib", "mage-client-*.jar")]:
    mains = glob.glob(os.path.join(libdir, mainpat))
    if not mains: continue
    want = set(mf_entries(mains[0]))
    for b in sorted(want):
        if not os.path.exists(os.path.join(libdir, b)):
            src = os.path.join(stage, b)
            if os.path.exists(src): shutil.copy2(src, os.path.join(libdir, b)); print("   +", b, "->", libdir)
            else: print("   !! unresolvable manifest dep:", b, "for", libdir); rc = 1
    for j in glob.glob(os.path.join(libdir, "*.jar")):
        b = os.path.basename(j)
        if b in want or b == os.path.basename(mains[0]) or b.startswith("mage"): continue
        stem = re.sub(r"-[0-9][0-9.]*[^/]*\.jar$", "", b)
        if any(w.startswith(stem + "-") for w in want):
            os.remove(j); print("   -", b, "(stale version)")
sys.exit(rc)
PY

# every plugin jar config.xml references must exist in plugins/ — upstream artifactId
# renames (booster-draft->boosterdraft, momir->momirfreeforall, 2026-08-02) otherwise
# strand the config; unresolvable refs fail the deploy LOUDLY so the config gets fixed.
echo ">> ensuring config-referenced plugins exist"
python3 - "$STAGE" "$BUNDLE" "$LIVE" <<'PY'
import os, re, sys, shutil
stage, bundle, live = sys.argv[1:4]
rc = 0
for cfg, plugdir in [(bundle + "/mage-server/config/config.xml", bundle + "/mage-server/plugins"),
                     (live + "/mage-server/config/config.xml", live + "/mage-server/plugins")]:
    if not os.path.exists(cfg): continue
    for j in sorted(set(re.findall(r'jar="(mage-[a-z0-9-]+-[0-9.]+\.jar)"', open(cfg).read()))):
        if not os.path.exists(os.path.join(plugdir, j)):
            src = os.path.join(stage, j)
            if os.path.exists(src): shutil.copy2(src, os.path.join(plugdir, j)); print("   +", j, "->", plugdir)
            else: print("   !! config.xml references missing plugin (upstream rename? fix config):", j); rc = 1
sys.exit(rc)
PY

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
