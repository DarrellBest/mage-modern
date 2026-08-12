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

# 2c. stage the repo's <playerTypes> block, version-stamped.
#
# WHY THIS EXISTS: swap_dir (below) only REPLACES jars that already exist in a target dir, and the
# config.xml step below only rewrites version stamps in the config that is already there. Neither
# mechanism can introduce a BRAND-NEW module. So a new AI plugin would build, ship into $STAGE, and
# then silently never reach the live server or its config -- the deploy reports success and the new
# player type simply never appears in the client. That is exactly what happened to
# mage-player-ai-commander: the module was added, deployed repeatedly, and never showed up, because
# the live config.xml still listed only Human/mad/monte carlo/draftbot.
#
# Syncing the repo's playerTypes block fixes both halves at once: the config gains the entry, and
# the existing "ensuring config-referenced plugins exist" step then copies the referenced jar in
# because it is now referenced. The rest of each config (adminPassword, ports, server-local
# settings) is left untouched -- only the playerTypes block is replaced.
python3 - "$STAGE" "$POMV" "Mage.Server/release/config/config.xml" <<'PY'
import re, sys, os
stage, pomv, repo_cfg = sys.argv[1:4]
src = open(repo_cfg).read().replace("${project.version}", pomv)
m = re.search(r"[ \t]*<playerTypes>.*?</playerTypes>", src, re.S)
if not m:
    print("   !! no <playerTypes> block in", repo_cfg); sys.exit(1)
open(os.path.join(stage, "playerTypes.xml"), "w").write(m.group(0))
print(">> staged playerTypes block (%d entries)" % m.group(0).count("<playerType "))
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
# Sync the playerTypes block from the repo BEFORE the version realign below, so any entry this
# adds gets stamped by the same sed. Backs each config up first: a malformed config.xml stops the
# server from booting at all, and this runs on a live install.
echo ">> syncing playerTypes from repo config"
python3 - "$STAGE/playerTypes.xml" "$BUNDLE/mage-server/config/config.xml" "$LIVE/mage-server/config/config.xml" <<'PY'
import re, sys, os, shutil
block_file, *cfgs = sys.argv[1:]
if not os.path.exists(block_file):
    print("   !! no staged playerTypes block; skipping"); sys.exit(0)
# newline="" everywhere: the deployed config.xml is CRLF while the repo template is LF, and
# Python's text mode would silently translate on read and write back LF -- rewriting all 215
# lines of a boot-critical file as a side effect of changing six of them, and making the .bak
# diff useless for seeing what actually changed.
with open(block_file, newline="") as fh:
    block_raw = fh.read()
for cfg in cfgs:
    if not os.path.exists(cfg):
        continue
    with open(cfg, newline="") as fh:
        text = fh.read()
    if not re.search(r"[ \t]*<playerTypes>.*?</playerTypes>", text, re.S):
        print("   !! no <playerTypes> block in", cfg, "-- leaving alone"); continue
    # match the target file's own line endings rather than imposing the template's
    crlf = text.count("\r\n") > text.count("\n") - text.count("\r\n")
    block = block_raw.replace("\r\n", "\n")
    if crlf:
        block = block.replace("\n", "\r\n")
    new = re.sub(r"[ \t]*<playerTypes>.*?</playerTypes>", lambda _: block, text, count=1, flags=re.S)
    if new == text:
        print("   = already current:", cfg); continue
    shutil.copy2(cfg, cfg + ".bak")     # boot-critical file; keep a rollback
    with open(cfg, "w", newline="") as fh:
        fh.write(new)
    was = len(re.findall(r"<playerType ", text)); now = len(re.findall(r"<playerType ", block))
    print("   updated %s (%d -> %d player types, backup at %s.bak)" % (cfg, was, now, cfg))
PY

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
if [ ! -d "$BUNDLE" ]; then
  echo "   !! $BUNDLE missing — can't re-zip the launcher update bundle." >&2
  echo "   !! Recover with: mkdir -p \"$BUNDLE\" && cd \"$BUNDLE\" && unzip \"$WEBZIP\"" >&2
  echo "   !! (restores the last known-good bundle as a base; this script will then re-swap fresh jars into it)" >&2
  exit 1
fi
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
