#!/usr/bin/env bash
# Admin CLI for the LIVE XMage server: list active tables/games and kill exactly one,
# without restarting the server.
#
#   tools/kill-game.sh --list
#   tools/kill-game.sh --kill <gameId-or-tableId> --yes
#
# It talks to the running server over the same JBoss Remoting connection the stock
# Mage.Server.Console admin GUI uses (SessionImpl -> MageServer.adminTableRemove), so it needs
# no server-side change and no deploy.
#
# The admin password is NOT stored in this repo. It is read at runtime from either
# $XMAGE_ADMIN_PASSWORD or, by default, the -adminPassword= argument in the live server's own
# start script ($XMAGE_SERVER_DIR/start-fork.sh). That file already holds the secret; copying it
# into version control would only widen the exposure.
#
# Build: javac only, against the jars the live server already runs with. Nothing here touches
# ~/.m2 or runs maven, so it is safe to use while a parameter sweep is running.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC_DIR="$HERE/admin-cli"
OUT_DIR="$SRC_DIR/out"

SERVER_DIR=${XMAGE_SERVER_DIR:-/home/user/Documents/xmage/xmage/mage-server}
LIB_DIR="$SERVER_DIR/lib"

# JBoss Remoting 2.5.4 needs a Java 8 runtime; on a modern JDK it dies reflecting into java.io.
# Prefer the JRE the server itself runs on.
JAVA_BIN=${XMAGE_JAVA:-/home/user/Documents/xmage/java/jre1.8.0_201/bin/java}
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
JAVAC_BIN=${XMAGE_JAVAC:-javac}

if [ ! -d "$LIB_DIR" ]; then
  echo "kill-game: no server jars at $LIB_DIR (set XMAGE_SERVER_DIR)" >&2
  exit 1
fi

# Wildcard classpath: the JVM and javac both expand lib/* themselves.
CP="$LIB_DIR/*:$SRC_DIR/conf"

# Recompile only when the source is newer than the class file.
if [ ! -f "$OUT_DIR/KillGame.class" ] || [ "$SRC_DIR/KillGame.java" -nt "$OUT_DIR/KillGame.class" ]; then
  mkdir -p "$OUT_DIR"
  # --release 8 so the classes load on the server's JRE 1.8
  "$JAVAC_BIN" --release 8 -nowarn -cp "$LIB_DIR/*" -d "$OUT_DIR" "$SRC_DIR/KillGame.java" 2>&1 \
    | grep -v 'source value 8 is obsolete\|target value 8 is obsolete\|To suppress warnings about obsolete\|warning: \[options\]' || true
  if [ ! -f "$OUT_DIR/KillGame.class" ]; then
    echo "kill-game: compile failed" >&2
    exit 1
  fi
fi

exec "$JAVA_BIN" -cp "$OUT_DIR:$CP" KillGame "$@"
