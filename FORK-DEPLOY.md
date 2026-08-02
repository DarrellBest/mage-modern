# Fork Deploy Runbook

Ops notes for **DarrellBest/mage-modern** (the XMage fork) and its Electron launcher.
Kept separate from the upstream `readme.md` so it never causes merge conflicts on an upstream pull.

## "go" — the full game deploy

When the request is **"go"** (or "deploy", "full loop", "pull the latest fork and deploy", "ship an update"):

```bash
ssh user@192.168.1.87 'cd ~/projects/mage-modern && ./tools/full-deploy.sh'
```

**The build home is the game server itself** (repo clone at `~/projects/mage-modern` on
.87; Maven in `~/.local`, pinned to JDK 21 via `~/.mavenrc`). The deploy scripts are
dual-home — they detect where they run, so the loop also works from a dev-box clone
(builds locally, ships over ssh) if that box has a real JDK (needs `ct.sym`; a JRE-only
install fails `--release 8` with "release version 8 not supported").

That one script is the whole loop. It:

1. **Sync** — fetch upstream (`magefree/mage`) → fast-forward `master` → merge into `ui-modernization` → push both. Aborts cleanly on merge conflicts.
2. **Build + ship + restart** — runs `tools/deploy-fork.sh`: full `mvn clean install -DskipTests`, rsync fork jars to the server, refresh the download bundle + live server, re-zip `mage-update_fork.zip`, bump `config.json` version (so every launcher detects an update), and `systemctl restart xmage-fork`.
3. **Restart verify** — confirms the LIVE process is the new `mage-server-<ver>.jar` and `:17171` is listening.
4. **Heap guard** — warns if heap `< 4 GB`.
5. **Version audit** — no stale/mixed jars anywhere (live server, live client, bundle, update zip).

**Report back** the stage results — especially `VERIFY: ALL GREEN ✓`. If stage 1 hits conflicts or verify fails, stop and surface it; don't force past it.

Friends pick up the update through the launcher.

## Infrastructure

- **Server / build host**: `user@192.168.1.87` (`user-X570-AORUS-XTREME`, x86_64, 24 cores/62 GB, passwordless sudo for `systemctl … xmage-fork`). Repo clones live in `~/projects/{mage-modern,Launcher}`; pushes use the server's own GitHub ssh key.
- **Live server install**: `~/Documents/xmage/xmage/` — `mage-server/` runs the game on `:17171`; heap is set in `mage-server/start-fork.sh` (currently `-Xmx8192m`).
- **Web/config** (`:17080`): `/var/www/html/config.json`, downloads in `/var/www/html/files/` (`mage-update_fork.zip`, `XMageLauncher-*.exe/.AppImage`).

## Launcher (separate — only when launcher code changes)

The Electron launcher (`~/projects/Launcher`) is a **different artifact** and is NOT part of `full-deploy.sh`. It only rebuilds when its own code changes. Build it on the **x86_64 server** (has wine + makensis; the aarch64 DGX cannot cross-build a working Windows exe):

```bash
# bump version in Launcher/electron/package.json first, then:
rsync -az --exclude node_modules --exclude dist ~/projects/Launcher/electron/ user@192.168.1.87:~/launcher-src/
ssh user@192.168.1.87 'cd ~/launcher-src && npm install \
  && npx electron-builder --win portable --x64 \
  && npx electron-builder --linux AppImage --x64 \
  && cp -f dist/XMageLauncher-*.exe dist/XMageLauncher-*.AppImage /var/www/html/files/'
```

Then hand over the download links:
`http://play.darrellbest.com:17080/files/XMageLauncher-<ver>.exe` (and `.AppImage`).

### macOS build (GitHub Actions — no mac hardware here)

electron-builder can only produce mac targets on macOS, so the dmg builds on a free
GitHub-hosted mac runner (`.github/workflows/mac-build.yml` in the Launcher repo):

```bash
# bump version in Launcher/electron/package.json, commit, then:
git tag mac-v<ver> && git push origin mac-v<ver>
# → builds a universal (Intel + Apple Silicon) dmg, ad-hoc signs it, publishes it as
#   a release asset. Pull it onto the server for distribution:
curl -L -o /tmp/XMageLauncher-<ver>.dmg \
  https://github.com/DarrellBest/Launcher/releases/download/mac-v<ver>/XMageLauncher-<ver>.dmg
scp /tmp/XMageLauncher-<ver>.dmg user@192.168.1.87:/var/www/html/files/
```

Mac link: `http://play.darrellbest.com:17080/files/XMageLauncher-<ver>.dmg`.
Mac-user caveats (no Apple Developer ID → not notarized):
- First launch: right-click → Open, or System Settings → Privacy & Security → "Open Anyway" (macOS 15+ removed the right-click bypass).
- Apple Silicon needs Rosetta 2 for the bundled x64 Java 8 (`softwareupdate --install-rosetta --agree-to-license`); the launcher itself runs natively.
- The mac Java tarball (`jre-8u201-macosx-x64.tar.gz`) is already hosted in `/var/www/html/files/java/` and the launcher already handles the mac JRE layout (`Contents/Home`).

## Hard-won gotchas (don't relearn these)

- **Always verify the restart** — confirm the running process is the new jar; don't just trust that the script restarted it. (`full-deploy.sh` stage 3 does this.)
- **Heap**: 1 GB OOM'd (`GC overhead limit exceeded`) on AI Commander games. It's `-Xmx8192m` in `start-fork.sh`; keep it ≥ 4 GB.
- **Version match**: client and server jars must be the *exact* same version (e.g. `1.4.60-V2`). The launcher's updater clears `lib/` before extracting so old + new jars don't collide → "wrong client version". (Launcher ≥ 1.1.1.)
- **Client graphics**: do NOT force `-Dsun.java2d.opengl=true` — it native-crashes (`EXCEPTION_ACCESS_VIOLATION` in `jvm.dll`) on Java 8/Windows. Launcher ≥ 1.2.0 defaults to Auto and has a ⚙ Settings panel (Auto/D3D/OpenGL/Software, memory, Java, etc.).
- **One client at a time** — the launcher blocks a second launch (shared H2 memory-mapped DB corrupts across instances).
- **Never edit upstream-shared files** (like `readme.md`) for fork-only notes — it causes recurring merge conflicts. Fork-only docs/config go in fork-only files.
