# Client Visual Atmosphere — Design

**Goal:** Add purely-visual "modern game" polish to the XMage Swing client — an animated arcane particle/atmosphere background, a glow behind the logo, and organized/hover-polished lobby panels — without changing any game functionality.

**Constraint (hard):** Game logic, networking, and all interactive behavior stay byte-for-byte the same. Every effect is a layer *behind* functional components or a styling-only change. Keep the change set small and isolated so upstream `magefree/mage` merges stay clean.

**Intensity / scope (approved):** "Rich but tasteful." Lobby/main-menu first (rich), then the in-game board (subtle), with a user toggle to disable.

---

## Architecture

One reusable, parameterized component does the heavy lifting:

`AnimatedBackgroundPanel extends mage.components.ImagePanel` becomes the backmost layer (`backgroundPane`). Its `paintComponent` renders, in order:
1. `super.paintComponent(g)` — the existing theme background image (unchanged behavior).
2. A soft radial **nebula tint** + **vignette** (Graphics2D radial `GradientPaint`/`RadialGradientPaint`).
3. A drifting **particle field** (soft glowing motes; additive-ish alpha).
4. A **glow** ellipse where the logo sits (lobby only).

A single `javax.swing.Timer` advances particle state and calls `repaint()`. The component is non-interactive: it is the backmost layer, so functional components paint on top and receive all events — no glass pane, no event interception.

**Intensity is a preset** (`AtmospherePreset`): density, max particle count, fps, particle size/speed ranges, palette, tint/vignette/glow alphas. Two presets — `LOBBY` (rich) and `INGAME` (sparse, low-alpha, slow) — let the same class serve both surfaces.

**Why this approach:** isolated to ~1 new class + small hooks (low merge-conflict surface), reuses the existing backmost layer, intensity-tunable, no new dependencies (hand-rolled Graphics2D, Java 8 safe).

### Rejected alternatives
- **Bespoke painted panels per surface:** higher polish ceiling but much more code and a larger upstream-merge conflict surface.
- **Add an animation/particle library:** new dependencies, Java 8 compat risk, scarce Swing particle libs — overkill.

---

## Components

### 1. `AnimatedBackgroundPanel` (new — `Mage.Client/.../components/AnimatedBackgroundPanel.java`)
- Holds a `List<Particle>` (x, y, size, vx, vy, baseAlpha, phase, color).
- `AtmospherePreset` (enum or small config object): `LOBBY`, `INGAME`. Fields: particleCount, fps, sizeMin/Max, speedMin/Max, palette (Color[]), tintAlpha, vignetteAlpha, glowAlpha, drawGlow (bool).
- `start()/stop()` controlling the `javax.swing.Timer`. Auto-pause when `!isShowing()` (via `addHierarchyListener`/`isShowing()` check in the tick) to save CPU.
- `tick()`: integrate positions, gentle sinusoidal alpha twinkle, wrap particles around edges; `repaint()`.
- `paintComponent(g)`: `super` (bg image) → nebula tint → vignette → particles (with `RenderingHints` antialias, soft `radial` per-mote or `fillOval` + low alpha) → optional logo glow at a configurable center rect.
- Palette from the arcane theme: `#c9a7ff`, `#a875ff`, `#6d3bd1`, `#46e6d6`, `#f3c97a`, `#ffffff` over void `#070512`.
- Respects a global enable flag (see Preferences) — if disabled, behaves exactly like a plain `ImagePanel` (no timer, no extra painting).

### 2. Lobby wire-in (modify `MageFrame.java`)
- In `setBackground()` (~line 549–554): construct `AnimatedBackgroundPanel(image, style, AtmospherePreset.LOBBY)` instead of `new ImagePanel(...)` when the effect is enabled; otherwise fall back to plain `ImagePanel`.
- In `addMageLabel()` (~line 577–610): tell the panel where the logo rect is so it can center the glow there (setter `setGlowRect(Rectangle)`), updated on the existing resize `ComponentListener`.

### 3. In-game wire-in (modify game background — phase 2)
- Behind the battlefield, use an `AnimatedBackgroundPanel` with `AtmospherePreset.INGAME` (≈25 particles, low alpha, ~20fps, no logo glow). Verify the battlefield's backmost layer during implementation; keep strictly subtle. Gated by the same toggle. Done only after the lobby is confirmed good.

### 4. Preferences toggle (modify `PreferencesDialog.java`)
- New boolean pref `KEY_ANIMATED_BACKGROUND` (default `true`) under the existing theme/appearance section: "Animated background (arcane atmosphere)".
- A getter used by `MageFrame.setBackground()` and the in-game wire-in. Changing it re-applies on next screen show (no live-toggle requirement for v1; applying on restart/reconnect is acceptable, live re-apply is a nice-to-have).

### 5. Lobby polish pass (modify styling only)
- Via FlatLaf `UIManager`/client properties in `GuiDisplayUtil.refreshThemeSettings()` and light touches in `TablesPanel`/chat:
  - Rounded "card" panels (`FlatLaf.style` / `putClientProperty("FlatLaf.style", "arc: 12")`) for the tables and chat containers.
  - Button/table-row hover feedback, selection arc, consistent padding/spacing, subtle section header labels.
- No component restructuring, no logic changes.

---

## Performance & Safety
- Particle counts capped (~70 lobby / ~25 in-game); timers ~30 fps lobby / ~20 fps in-game.
- Timer paused when the panel is not showing (window minimized, or lobby hidden during a game).
- Antialiasing on, but particle paint kept cheap (`fillOval` + cached soft-dot image if needed for glow).
- All effects render behind functional components; the animated panel never sits on top and never consumes input events.
- Isolated change set → preserves clean upstream merges.

## Testing
- **Unit:** particle update math — positions stay finite and wrap within bounds after N ticks; preset disabled ⇒ no timer/extra paint.
- **Manual on .87:** launch client (bundled Java 8), screenshot lobby (verify atmosphere + glow, no rectangle artifacts), toggle off (verify plain background), then phase 2 in-game screenshot (verify subtle). 
- **Functional smoke:** connect to the fork server, open the tables list, start a game vs AI — confirm unchanged behavior and no input interception.

## Files
- **Create:** `Mage.Client/src/main/java/mage/client/components/AnimatedBackgroundPanel.java`
- **Modify:** `Mage.Client/src/main/java/mage/client/MageFrame.java` (setBackground, addMageLabel glow rect)
- **Modify:** `Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java` (toggle + key)
- **Modify (styling):** `Mage.Client/src/main/java/mage/client/util/gui/GuiDisplayUtil.java` and light touches in `Mage.Client/src/main/java/mage/client/table/TablesPanel.java`
- **Phase 2:** the in-game battlefield background wire-in (file TBD-by-inspection during phase 2).

## Deploy
Ship via existing `tools/deploy-fork.sh` (build → bundle → version bump → restart live server). Bumps the launcher version so clients see the update.
