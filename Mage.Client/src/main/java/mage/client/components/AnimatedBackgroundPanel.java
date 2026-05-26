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
