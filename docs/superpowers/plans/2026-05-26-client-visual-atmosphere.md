# Client Visual Atmosphere Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an animated arcane particle/atmosphere background (plus a glow behind the logo) to the XMage Swing client — lobby first (rich), then in-game (subtle) — with zero changes to game functionality.

**Architecture:** One reusable `AnimatedBackgroundPanel extends mage.components.ImagePanel` becomes the backmost layer. It paints the existing theme background (`super.paintComponent`), then a nebula tint, particle field, optional logo glow, and a vignette. A `javax.swing.Timer` drives it; intensity comes from an `AtmospherePreset`. It is the backmost layer, so it never intercepts input. A pref key gates it (default on).

**Tech Stack:** Java 8, Swing/AWT Graphics2D (RadialGradientPaint), `javax.swing.Timer`. No new dependencies. JUnit 4 (existing in `Mage.Client/src/test`).

**Build/deploy:** dev box has Maven + JDK 21; deploy via `tools/deploy-fork.sh`. Verify visually by launching the client on .87 (bundled Java 8) and screenshotting.

---

## File Structure

- **Create:** `Mage.Client/src/main/java/mage/client/components/AnimatedBackgroundPanel.java` — the whole effect (particles, presets, painting). Self-contained.
- **Create:** `Mage.Client/src/test/java/mage/client/components/AnimatedBackgroundPanelTest.java` — unit test for the pure `wrap` math.
- **Modify:** `Mage.Client/src/main/java/mage/client/MageFrame.java` — use the animated panel for the lobby background (~line 549–552); feed the logo glow rect in the resize listener (~line 353–355).
- **Modify:** `Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java` — add `KEY_ANIMATED_BACKGROUND` constant (~line 97).
- **Phase 2 / polish:** `GuiDisplayUtil.java` (a few FlatLaf keys) and the in-game background wire-in.

---

## Task 1: `AnimatedBackgroundPanel` with a unit-tested `wrap()` helper

**Files:**
- Create: `Mage.Client/src/main/java/mage/client/components/AnimatedBackgroundPanel.java`
- Test: `Mage.Client/src/test/java/mage/client/components/AnimatedBackgroundPanelTest.java`

- [ ] **Step 1: Write the failing test**

`Mage.Client/src/test/java/mage/client/components/AnimatedBackgroundPanelTest.java`:
```java
package mage.client.components;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AnimatedBackgroundPanelTest {

    @Test
    public void wrapReentersFromOppositeEdge() {
        // below min -> comes back in from max side
        assertEquals(95f, AnimatedBackgroundPanel.wrap(-5f, 0f, 100f), 0.001f);
        // above max -> comes back in from min side
        assertEquals(5f, AnimatedBackgroundPanel.wrap(105f, 0f, 100f), 0.001f);
        // inside range -> unchanged
        assertEquals(50f, AnimatedBackgroundPanel.wrap(50f, 0f, 100f), 0.001f);
    }

    @Test
    public void wrapHandlesNonPositiveSpan() {
        // degenerate span -> value returned as-is (no infinite loop)
        assertEquals(7f, AnimatedBackgroundPanel.wrap(7f, 10f, 10f), 0.001f);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/dbest/projects/mage-modern && mvn -q -pl Mage.Client test -Dtest=AnimatedBackgroundPanelTest`
Expected: FAIL — `AnimatedBackgroundPanel` does not exist / does not compile.

- [ ] **Step 3: Write the implementation**

`Mage.Client/src/main/java/mage/client/components/AnimatedBackgroundPanel.java`:
```java
package mage.client.components;

import mage.client.dialog.PreferencesDialog;
import mage.components.ImagePanel;
import mage.components.ImagePanelStyle;

import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Backmost desktop layer that draws the theme background plus a purely-visual
 * arcane atmosphere: nebula tint, drifting glowing particles, an optional glow
 * behind the logo, and a vignette. Non-interactive (it is the bottom layer, so
 * all functional components paint on top and receive every event). No game
 * logic is touched. Gated by {@link PreferencesDialog#KEY_ANIMATED_BACKGROUND}.
 */
public class AnimatedBackgroundPanel extends ImagePanel {

    /** Tunable look. Lobby is rich; in-game is sparse, dim and slow. */
    public enum AtmospherePreset {
        //      count fps sizeMin sizeMax spdMin spdMax tint vignette glow
        LOBBY(    70,  33,  1.0f,   3.2f,   4f,    16f,  0.55f, 0.85f, true),
        INGAME(   26,  20,  0.8f,   2.4f,   6f,    20f,  0.30f, 0.55f, false);

        final int count, fps;
        final float sizeMin, sizeMax, speedMin, speedMax, tintAlpha, vignetteAlpha;
        final boolean drawGlow;

        AtmospherePreset(int count, int fps, float sizeMin, float sizeMax,
                         float speedMin, float speedMax, float tintAlpha,
                         float vignetteAlpha, boolean drawGlow) {
            this.count = count; this.fps = fps;
            this.sizeMin = sizeMin; this.sizeMax = sizeMax;
            this.speedMin = speedMin; this.speedMax = speedMax;
            this.tintAlpha = tintAlpha; this.vignetteAlpha = vignetteAlpha;
            this.drawGlow = drawGlow;
        }
    }

    private static final Color[] PALETTE = {
        new Color(0xC9, 0xA7, 0xFF), new Color(0xC9, 0xA7, 0xFF), // mostly soft purple
        new Color(0xA8, 0x75, 0xFF), new Color(0x46, 0xE6, 0xD6), // arcane + teal
        new Color(0xF3, 0xC9, 0x7A), new Color(0xFF, 0xFF, 0xFF)  // gold + white sparks
    };
    private static final Color VOID = new Color(7, 5, 18);
    private static final float MARGIN = 12f;

    private static final class Particle {
        float x, y, size, vx, vy, baseAlpha, phase;
        Color color;
    }

    private final AtmospherePreset preset;
    private final List<Particle> particles = new ArrayList<>();
    private final Random rnd = new Random();
    private final Timer timer;
    private Rectangle glowRect;
    private long lastTickNanos;

    public AnimatedBackgroundPanel(BufferedImage image, ImagePanelStyle style, AtmospherePreset preset) {
        super(image, style);
        this.preset = preset;
        int delay = Math.max(1, 1000 / preset.fps);
        this.timer = new Timer(delay, e -> tick());
        this.timer.setCoalesce(true);
    }

    /** Tells the panel where the logo sits so the glow can sit behind it. */
    public void setGlowRect(Rectangle r) {
        this.glowRect = r != null ? new Rectangle(r) : null;
    }

    private static boolean enabled() {
        return "true".equals(PreferencesDialog.getCachedValue(PreferencesDialog.KEY_ANIMATED_BACKGROUND, "true"));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (enabled()) {
            ensureParticles();
            lastTickNanos = System.nanoTime();
            timer.start();
        }
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    /** Pure, unit-tested: keep a coordinate inside [min,max], re-entering the far edge. */
    static float wrap(float value, float min, float max) {
        float span = max - min;
        if (span <= 0f) {
            return value;
        }
        float v = value;
        while (v < min) {
            v += span;
        }
        while (v > max) {
            v -= span;
        }
        return v;
    }

    private void ensureParticles() {
        int w = getWidth() > 0 ? getWidth() : 1024;
        int h = getHeight() > 0 ? getHeight() : 768;
        particles.clear();
        for (int i = 0; i < preset.count; i++) {
            Particle p = new Particle();
            p.x = rnd.nextFloat() * w;
            p.y = rnd.nextFloat() * h;
            p.size = preset.sizeMin + rnd.nextFloat() * (preset.sizeMax - preset.sizeMin);
            double ang = rnd.nextFloat() * Math.PI * 2;
            float spd = preset.speedMin + rnd.nextFloat() * (preset.speedMax - preset.speedMin);
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.baseAlpha = 0.2f + rnd.nextFloat() * 0.5f;
            p.phase = rnd.nextFloat() * (float) Math.PI * 2;
            p.color = PALETTE[rnd.nextInt(PALETTE.length)];
            particles.add(p);
        }
    }

    private void tick() {
        if (!isShowing()) {
            return; // paused when hidden (minimized / behind a game)
        }
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (particles.isEmpty()) {
            ensureParticles();
        }
        long now = System.nanoTime();
        float dt = (now - lastTickNanos) / 1_000_000_000f;
        lastTickNanos = now;
        if (dt > 0.1f) {
            dt = 0.1f; // clamp after pauses
        }
        for (Particle p : particles) {
            p.x = wrap(p.x + p.vx * dt, -MARGIN, w + MARGIN);
            p.y = wrap(p.y + p.vy * dt, -MARGIN, h + MARGIN);
            p.phase += dt;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); // existing theme background image
        if (!enabled()) {
            return;
        }
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paintNebula(g2, w, h);
        if (preset.drawGlow && glowRect != null) {
            float cx = (float) glowRect.getCenterX();
            float cy = (float) glowRect.getCenterY();
            drawRadial(g2, cx, cy, glowRect.width * 0.6f, new Color(0xA8, 0x75, 0xFF), 0.22f, 0f, 1f);
        }
        for (Particle p : particles) {
            float twinkle = 0.6f + 0.4f * (float) Math.sin(p.phase * 2.0);
            paintMote(g2, p, p.baseAlpha * twinkle);
        }
        paintVignette(g2, w, h);
        g2.dispose();
    }

    private void paintNebula(Graphics2D g2, int w, int h) {
        float a = preset.tintAlpha;
        float big = Math.max(w, h);
        drawRadial(g2, w * 0.50f, h * 0.38f, big * 0.55f, new Color(0x6D, 0x3B, 0xD1), 0.45f * a, 0f, 1f);
        drawRadial(g2, w * 0.16f, h * 0.82f, big * 0.40f, new Color(0x46, 0xE6, 0xD6), 0.10f * a, 0f, 1f);
        drawRadial(g2, w * 0.86f, h * 0.20f, big * 0.42f, new Color(0xF3, 0xC9, 0x7A), 0.08f * a, 0f, 1f);
    }

    private void paintVignette(Graphics2D g2, int w, int h) {
        float r = (float) Math.hypot(w, h) / 2f;
        Color clear = new Color(VOID.getRed(), VOID.getGreen(), VOID.getBlue(), 0);
        Color dark = new Color(VOID.getRed(), VOID.getGreen(), VOID.getBlue(), clampByte(preset.vignetteAlpha));
        RadialGradientPaint p = new RadialGradientPaint(
            new Point2D.Float(w / 2f, h / 2f), r,
            new float[]{0.55f, 1f}, new Color[]{clear, dark});
        g2.setPaint(p);
        g2.fillRect(0, 0, w, h);
    }

    private void paintMote(Graphics2D g2, Particle p, float alpha) {
        float r = Math.max(1f, p.size * 3f);
        drawRadialAt(g2, p.x, p.y, r, p.color, alpha);
    }

    /** Radial blob filling the whole panel (for nebula/glow). */
    private void drawRadial(Graphics2D g2, float cx, float cy, float r, Color c,
                            float alpha, float stop0, float stop1) {
        if (r <= 0) {
            return;
        }
        Color c1 = new Color(c.getRed(), c.getGreen(), c.getBlue(), clampByte(alpha));
        Color c2 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        RadialGradientPaint p = new RadialGradientPaint(
            new Point2D.Float(cx, cy), r, new float[]{stop0, stop1}, new Color[]{c1, c2});
        g2.setPaint(p);
        g2.fillRect(0, 0, getWidth(), getHeight());
    }

    /** Radial blob bounded to a small oval (for a single mote — cheaper than filling the panel). */
    private void drawRadialAt(Graphics2D g2, float cx, float cy, float r, Color c, float alpha) {
        Color c1 = new Color(c.getRed(), c.getGreen(), c.getBlue(), clampByte(alpha));
        Color c2 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        RadialGradientPaint p = new RadialGradientPaint(
            new Point2D.Float(cx, cy), r, new float[]{0f, 1f}, new Color[]{c1, c2});
        g2.setPaint(p);
        g2.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
    }

    private static int clampByte(float alpha) {
        int v = Math.round(alpha * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/dbest/projects/mage-modern && mvn -q -pl Mage.Client test -Dtest=AnimatedBackgroundPanelTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/components/AnimatedBackgroundPanel.java \
        Mage.Client/src/test/java/mage/client/components/AnimatedBackgroundPanelTest.java
git commit -m "feat(client): AnimatedBackgroundPanel — arcane particle/atmosphere layer"
```

---

## Task 2: Pref key + wire the lobby background to the animated panel

**Files:**
- Modify: `Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java:97`
- Modify: `Mage.Client/src/main/java/mage/client/MageFrame.java` (setBackground ~549–552, resize listener ~353–355, imports)

- [ ] **Step 1: Add the pref key constant**

In `PreferencesDialog.java`, directly after the existing line:
```java
    public static final String KEY_BACKGROUND_IMAGE_DEFAULT = "backgroundImagedDefault";
```
add:
```java
    public static final String KEY_ANIMATED_BACKGROUND = "animatedBackground";
```

- [ ] **Step 2: Use the animated panel for the lobby background**

In `MageFrame.java` `setBackground()`, replace this block:
```java
            } else {
                InputStream is = this.getClass().getResourceAsStream(PreferencesDialog.getCurrentTheme().getLoginBackgroundPath());
                BufferedImage background = ImageIO.read(is);
                backgroundPane = new ImagePanel(background, ImagePanelStyle.SCALED);
            }
```
with:
```java
            } else {
                InputStream is = this.getClass().getResourceAsStream(PreferencesDialog.getCurrentTheme().getLoginBackgroundPath());
                BufferedImage background = ImageIO.read(is);
                if ("true".equals(PreferencesDialog.getCachedValue(PreferencesDialog.KEY_ANIMATED_BACKGROUND, "true"))) {
                    backgroundPane = new AnimatedBackgroundPanel(background, ImagePanelStyle.SCALED,
                            AnimatedBackgroundPanel.AtmospherePreset.LOBBY);
                } else {
                    backgroundPane = new ImagePanel(background, ImagePanelStyle.SCALED);
                }
            }
```

- [ ] **Step 3: Feed the logo glow rect on resize**

In `MageFrame.java`, in the `desktopPane` `componentResized` listener, replace:
```java
                if (title != null) {
                    title.setBounds((int) (width - titleRectangle.getWidth()) / 2, (int) (height - titleRectangle.getHeight()) / 2, titleRectangle.width, titleRectangle.height);
                }
```
with:
```java
                if (title != null) {
                    title.setBounds((int) (width - titleRectangle.getWidth()) / 2, (int) (height - titleRectangle.getHeight()) / 2, titleRectangle.width, titleRectangle.height);
                    if (backgroundPane instanceof AnimatedBackgroundPanel) {
                        ((AnimatedBackgroundPanel) backgroundPane).setGlowRect(title.getBounds());
                    }
                }
```

- [ ] **Step 4: Add the import**

In `MageFrame.java`, add to the imports (with the other `mage.client.components.*` imports):
```java
import mage.client.components.AnimatedBackgroundPanel;
```
(If the file already does `import mage.client.components.*;`, skip this.)

- [ ] **Step 5: Compile**

Run: `cd /home/dbest/projects/mage-modern && mvn -q -pl Mage.Client compile`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 6: Commit**

```bash
git add Mage.Client/src/main/java/mage/client/dialog/PreferencesDialog.java \
        Mage.Client/src/main/java/mage/client/MageFrame.java
git commit -m "feat(client): use AnimatedBackgroundPanel for lobby + logo glow"
```

---

## Task 3: Build, deploy, and verify the lobby on .87

**Files:** none (build/deploy/verify only)

- [ ] **Step 1: Deploy via the existing pipeline**

Run: `cd /home/dbest/projects/mage-modern && ./tools/deploy-fork.sh`
Expected: full build, jars shipped, bundle re-zipped, config version bumped, `xmage-fork` restarted.

- [ ] **Step 2: Launch the client on .87 and screenshot the lobby**

Run (on .87 via ssh, DISPLAY=:0, bundled Java 8, `-cp lib/*` so the new jar is picked up):
```bash
ssh user@192.168.1.87 'export DISPLAY=:0 XAUTHORITY=/run/user/1000/gdm/Xauthority; cd /home/user/Documents/xmage/xmage/mage-client; pkill -f mage-client; sleep 2; setsid /home/user/Documents/xmage/java/jre1.8.0_201/bin/java -Xmx2000m -Dsun.java2d.xrender=true -cp "lib/*" mage.client.MageFrame >/tmp/cl.log 2>&1 </dev/null & disown; for i in $(seq 1 30); do W=$(wmctrl -lx|grep -i MageFrame|grep -iv launcher|head -1|cut -d" " -f1); [ -n "$W" ] && break; sleep 2; done; wmctrl -i -r "$W" -b add,maximized_vert,maximized_horz; wmctrl -i -a "$W"; sleep 5; ffmpeg -y -f x11grab -i :0.0 -frames:v 1 /tmp/lobby.png >/dev/null 2>&1'
scp user@192.168.1.87:/tmp/lobby.png /tmp/lobby.png
```
Then `Read /tmp/lobby.png`.
Expected: dark arcane backdrop with drifting glowing particles + a soft glow behind the XMAGE logo. No rectangular artifacts. Toolbar/menus look normal.

- [ ] **Step 3: Confirm no input interception (functional smoke)**

Verify the screenshot shows the toolbar buttons and (if a connect dialog is up) that it is interactive. Optionally connect to the fork server and open the tables list to confirm clicks work. The animated panel is the backmost layer, so this should be unaffected.

- [ ] **Step 4: Commit (none needed) — record result**

No code change. If the lobby looks right, proceed to Phase 2. If particles are too strong/weak, adjust `AtmospherePreset.LOBBY` numbers in `AnimatedBackgroundPanel.java` and redeploy (repeat Task 3).

---

## Task 4 (Phase 2): Subtle in-game atmosphere

**Files:**
- Modify: the battlefield/game background layer — **identify the exact class/line first** by reading how the game screen sets its background (start from `mage/client/game/GamePanel.java` and the battlefield background; search for where the in-game background image/panel is created). Apply the same swap pattern as Task 2 Step 2, using `AtmospherePreset.INGAME`.

- [ ] **Step 1: Locate the in-game background**

Run: `cd /home/dbest/projects/mage-modern && grep -rn "ImagePanel\|setBackground\|getBackground" Mage.Client/src/main/java/mage/client/game/ | grep -i "panel\|background" | head`
Read the matched class to find where the game's backmost background panel is constructed.

- [ ] **Step 2: Swap to the INGAME preset (only if a clean backmost ImagePanel exists)**

At the construction site found in Step 1, mirror Task 2 Step 2: if `KEY_ANIMATED_BACKGROUND` is "true", construct `new AnimatedBackgroundPanel(image, style, AnimatedBackgroundPanel.AtmospherePreset.INGAME)` instead of the plain panel. If the game background is not a simple `ImagePanel` (e.g. it is painted differently), STOP and report — do not force it; the lobby effect already satisfies the core goal.

- [ ] **Step 3: Compile**

Run: `cd /home/dbest/projects/mage-modern && mvn -q -pl Mage.Client compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Deploy + verify in a real game**

Deploy (`./tools/deploy-fork.sh`), launch the client on .87 (Task 3 Step 2 command), start a game vs AI, screenshot the battlefield. Expected: a faint, slow particle atmosphere behind the board that does not obscure cards or hurt readability. If it competes with the board visually, reduce `AtmospherePreset.INGAME` counts/alpha and redeploy.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(client): subtle in-game arcane atmosphere (INGAME preset)"
```

---

## Notes / deliberate scope decisions

- **Visible Preferences checkbox is deferred.** The `PreferencesDialog` UI is a NetBeans-generated GroupLayout form; adding a checkbox there is high-churn and enlarges the upstream-merge surface for little benefit. v1 ships the `KEY_ANIMATED_BACKGROUND` key (default `true`), which already lets the effect be turned off via the prefs store. A visible checkbox can be added later as a small standalone task if wanted.
- **Lobby polish pass (rounded cards / hover) is intentionally out of this plan.** The animated background is the flagship visual win; the FlatLaf styling pass is lower-impact and can be a separate, small follow-up once the atmosphere lands. Keeping this plan focused preserves the small, merge-friendly change set.
- **Performance:** counts are capped (70 lobby / 26 in-game), timers coalesced, and the tick is a no-op when `!isShowing()`. If profiling shows cost, switch motes from per-frame `RadialGradientPaint` to a cached soft-dot sprite.
