package mage.client.components;

import mage.client.dialog.PreferencesDialog;
import mage.components.ImagePanel;
import mage.components.ImagePanelStyle;

import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
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
 *
 * <p>Performance: the static layers (theme image + nebula + glow + vignette) are
 * rendered once into {@link #bgCache} and only rebuilt on resize / glow change.
 * Each frame just blits that cache (XRender-accelerated) and draws the moving
 * motes as small pre-rendered sprites — no per-frame full-screen gradients.
 */
public class AnimatedBackgroundPanel extends ImagePanel {

    /** Tunable look. Lobby is rich; in-game is sparse, dim and slow. */
    public enum AtmospherePreset {
        //      count fps sizeMin sizeMax spdMin spdMax aMin  aMax  tint vignette glow
        LOBBY(   110,  33,  1.2f,   3.4f,   4f,    16f, 0.30f, 0.85f, 0.55f, 0.85f, true),
        INGAME(   28,  20,  0.8f,   2.4f,   6f,    20f, 0.18f, 0.45f, 0.30f, 0.55f, true);

        final int count, fps;
        final float sizeMin, sizeMax, speedMin, speedMax, alphaMin, alphaMax, tintAlpha, vignetteAlpha;
        final boolean drawGlow;

        AtmospherePreset(int count, int fps, float sizeMin, float sizeMax,
                         float speedMin, float speedMax, float alphaMin, float alphaMax,
                         float tintAlpha, float vignetteAlpha, boolean drawGlow) {
            this.count = count; this.fps = fps;
            this.sizeMin = sizeMin; this.sizeMax = sizeMax;
            this.speedMin = speedMin; this.speedMax = speedMax;
            this.alphaMin = alphaMin; this.alphaMax = alphaMax;
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
    private static final int SPRITE = 32; // pre-rendered soft-dot sprite size (px)

    private static final class Particle {
        float x, y, size, vx, vy, baseAlpha, phase;
        int colorIdx;
    }

    private final AtmospherePreset preset;
    private final List<Particle> particles = new ArrayList<>();
    private final Random rnd = new Random();
    private final Timer timer;
    private Rectangle glowRect;
    private long lastTickNanos;
    private int seededW = -1, seededH = -1;

    // cached static layers + mote sprites
    private BufferedImage bgCache;
    private int cacheW = -1, cacheH = -1;
    private Rectangle cacheGlow;
    private BufferedImage[] sprites;
    // counter-rotating arcane sigils (lobby only) — ported from the launcher
    private BufferedImage sigilOuter, sigilInner;
    private float angleOuter = 0f, angleInner = 0f;
    private static final int SIGIL_PX = 1024; // bake once, scale at paint

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
        seededW = w;
        seededH = h;
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
            p.baseAlpha = preset.alphaMin + rnd.nextFloat() * (preset.alphaMax - preset.alphaMin);
            p.phase = rnd.nextFloat() * (float) Math.PI * 2;
            p.colorIdx = rnd.nextInt(PALETTE.length);
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
        if (particles.isEmpty() || w != seededW || h != seededH) {
            ensureParticles(); // (re)distribute across the full current size, incl. after maximize
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
        // sigils: outer 60s clockwise, inner 40s reverse — matches the launcher exactly
        angleOuter = (float) ((angleOuter + dt * Math.PI * 2.0 / 60.0) % (Math.PI * 2.0));
        angleInner = (float) ((angleInner - dt * Math.PI * 2.0 / 40.0) % (Math.PI * 2.0));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (!enabled() || w <= 0 || h <= 0) {
            super.paintComponent(g); // plain theme background
            return;
        }
        if (sprites == null) {
            buildSprites();
        }
        if (bgCache == null || cacheW != w || cacheH != h || glowChanged()) {
            rebuildCache(w, h);
        }
        g.drawImage(bgCache, 0, 0, null); // accelerated blit of all static layers
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // counter-rotating arcane sigils behind the logo (lobby preset only)
        if (preset.drawGlow) {
            if (sigilOuter == null) buildSigils();
            float cx = w / 2f, cy = h / 2f, big = Math.min(w, h);
            paintSigil(g2, sigilOuter, cx, cy, big * 0.55f, angleOuter, 0.90f);
            paintSigil(g2, sigilInner, cx, cy, big * 0.36f, angleInner, 0.75f);
        }
        for (Particle p : particles) {
            float twinkle = 0.6f + 0.4f * (float) Math.sin(p.phase * 2.0);
            float alpha = clamp01(p.baseAlpha * twinkle);
            float d = p.size * 6f; // displayed glow diameter
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.drawImage(sprites[p.colorIdx], (int) (p.x - d / 2f), (int) (p.y - d / 2f),
                    (int) d, (int) d, null);
        }
        g2.dispose();
    }

    private boolean glowChanged() {
        if (cacheGlow == null && glowRect == null) {
            return false;
        }
        return cacheGlow == null || !cacheGlow.equals(glowRect);
    }

    /** Render theme bg + nebula + glow + vignette once into an offscreen image. */
    private void rebuildCache(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // pure void base for the lobby preset (the theme background image was drowning the sigils);
        // in-game still uses the bg image so the battlefield reads through
        if (preset.drawGlow) {
            g2.setColor(VOID);
            g2.fillRect(0, 0, w, h);
        } else {
            super.paintComponent(g2);
        }
        paintNebula(g2, w, h);
        if (preset.drawGlow && glowRect != null) {
            drawRadial(g2, (float) glowRect.getCenterX(), (float) glowRect.getCenterY(),
                    glowRect.width * 0.6f, new Color(0xA8, 0x75, 0xFF), 0.22f, w, h);
        }
        paintVignette(g2, w, h);
        g2.dispose();
        bgCache = img;
        cacheW = w;
        cacheH = h;
        cacheGlow = glowRect != null ? new Rectangle(glowRect) : null;
    }

    /** One soft white-core radial dot per palette colour, built once. */
    private void buildSprites() {
        sprites = new BufferedImage[PALETTE.length];
        float rad = SPRITE / 2f;
        for (int i = 0; i < PALETTE.length; i++) {
            BufferedImage s = new BufferedImage(SPRITE, SPRITE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = s.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = PALETTE[i];
            Color core = new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
            Color edge = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(rad, rad), rad,
                    new float[]{0f, 1f}, new Color[]{core, edge}));
            g.fillRect(0, 0, SPRITE, SPRITE);
            g.dispose();
            sprites[i] = s;
        }
    }

    private void paintSigil(Graphics2D g2, BufferedImage img, float cx, float cy, float size, float angle, float alpha) {
        AffineTransform t = AffineTransform.getTranslateInstance(cx, cy);
        t.rotate(angle);
        t.scale(size / img.getWidth(), size / img.getHeight());
        t.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
        Graphics2D gg = (Graphics2D) g2.create();
        gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        gg.drawImage(img, t, null);
        gg.dispose();
    }

    private void buildSigils() {
        sigilOuter = makeSigil(SIGIL_PX, 0.7f, false);
        sigilInner = makeSigil(SIGIL_PX, 1.0f, true);
    }

    /** Render the launcher's SVG sigil (viewBox 0..200) into a centered, scaled ARGB image. */
    private BufferedImage makeSigil(int size, float strokeAtVB, boolean inner) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float scale = size / 200f;
        g.translate(size / 2f, size / 2f);
        g.scale(scale, scale);
        g.translate(-100, -100); // viewBox center -> shift so SVG coords map directly
        // glow pass (wide, dim soft purple)
        g.setColor(new Color(0xA8, 0x75, 0xFF, 80));
        g.setStroke(new BasicStroke(strokeAtVB * 3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawSigilShapes(g, inner);
        // sharp pass (crisp arcane bright)
        g.setColor(new Color(0xC9, 0xA7, 0xFF, 220));
        g.setStroke(new BasicStroke(strokeAtVB, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawSigilShapes(g, inner);
        g.dispose();
        return img;
    }

    private void drawSigilShapes(Graphics2D g, boolean inner) {
        if (inner) {
            // <circle r=90> + <polygon points="100,20 160,140 40,140" stroke-dasharray="3 6">
            g.draw(new Ellipse2D.Float(10, 10, 180, 180));
            Stroke s = g.getStroke();
            if (s instanceof BasicStroke) {
                BasicStroke b = (BasicStroke) s;
                g.setStroke(new BasicStroke(b.getLineWidth(), b.getEndCap(), b.getLineJoin(), 10f, new float[]{3f, 6f}, 0f));
            }
            Path2D tri = new Path2D.Float();
            tri.moveTo(100, 20); tri.lineTo(160, 140); tri.lineTo(40, 140); tri.closePath();
            g.draw(tri);
            g.setStroke(s);
        } else {
            // <circle r=96>, <circle r=74>, two triangles -> star of david, <circle r=40>, crosshair, <circle r=8>
            g.draw(new Ellipse2D.Float(4, 4, 192, 192));
            g.draw(new Ellipse2D.Float(26, 26, 148, 148));
            Path2D t1 = new Path2D.Float();
            t1.moveTo(100, 12); t1.lineTo(176, 150); t1.lineTo(24, 150); t1.closePath();
            g.draw(t1);
            Path2D t2 = new Path2D.Float();
            t2.moveTo(100, 188); t2.lineTo(24, 50); t2.lineTo(176, 50); t2.closePath();
            g.draw(t2);
            g.draw(new Ellipse2D.Float(60, 60, 80, 80));
            g.draw(new Line2D.Float(100, 60, 100, 140));
            g.draw(new Line2D.Float(60, 100, 140, 100));
            g.draw(new Ellipse2D.Float(92, 92, 16, 16));
        }
    }

    private void paintNebula(Graphics2D g2, int w, int h) {
        float a = preset.tintAlpha;
        float big = Math.max(w, h);
        drawRadial(g2, w * 0.50f, h * 0.38f, big * 0.55f, new Color(0x6D, 0x3B, 0xD1), 0.45f * a, w, h);
        drawRadial(g2, w * 0.16f, h * 0.82f, big * 0.40f, new Color(0x46, 0xE6, 0xD6), 0.10f * a, w, h);
        drawRadial(g2, w * 0.86f, h * 0.20f, big * 0.42f, new Color(0xF3, 0xC9, 0x7A), 0.08f * a, w, h);
    }

    private void paintVignette(Graphics2D g2, int w, int h) {
        float r = (float) Math.hypot(w, h) / 2f;
        Color clear = new Color(VOID.getRed(), VOID.getGreen(), VOID.getBlue(), 0);
        Color dark = new Color(VOID.getRed(), VOID.getGreen(), VOID.getBlue(), clampByte(preset.vignetteAlpha));
        g2.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, h / 2f), r,
                new float[]{0.55f, 1f}, new Color[]{clear, dark}));
        g2.fillRect(0, 0, w, h);
    }

    private void drawRadial(Graphics2D g2, float cx, float cy, float r, Color c, float alpha, int w, int h) {
        if (r <= 0) {
            return;
        }
        Color c1 = new Color(c.getRed(), c.getGreen(), c.getBlue(), clampByte(alpha));
        Color c2 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
        g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), r,
                new float[]{0f, 1f}, new Color[]{c1, c2}));
        g2.fillRect(0, 0, w, h);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int clampByte(float alpha) {
        int v = Math.round(alpha * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
