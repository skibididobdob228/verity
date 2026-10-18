package dev.verity.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.verity.VerityMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Verity's face, redrawn into a small texture whenever it changes: two eyes and a mouth rendered from
 * signed distances, so he can blink, talk, frown and grin with far too many teeth.
 */
public final class FaceTexture implements AutoCloseable {
    public static final int SIZE = 256;
    /** The texture covers this many degrees of the ball to either side of the centre of the face. */
    public static final float HALF_ANGLE_DEG = 52f;
    private static final float PX = 2f / SIZE;
    private static final int INK = 0x100A04;        // the brown-black of the original brush strokes (RGB)
    private static int counter;

    public final ResourceLocation id;
    private final DynamicTexture texture;
    private int drawn = Integer.MIN_VALUE;

    public FaceTexture() {
        texture = new DynamicTexture(SIZE, SIZE, true);
        texture.setFilter(true, false);
        id = VerityMod.id("dynamic/face_" + counter++);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    public void draw(FaceParams p) {
        int fp = p.fingerprint();
        if (fp == drawn) return;
        drawn = fp;
        NativeImage img = texture.getPixels();
        if (img == null) return;
        img.fillRect(0, 0, SIZE, SIZE, 0);

        float eyeRy = p.eyeRy * Math.max(p.eyeOpen, 0.07f);
        float ecx = p.eyeX, ecy = p.eyeY + p.lookY * 0.02f, look = p.lookX * 0.025f;
        float mw = Math.max(p.mouthW, 0.02f);
        float cTop = p.curve * (1f - 0.55f * p.open), cBot = p.curve + p.open * 0.42f;
        float dimple = clamp(p.curve / 0.25f) * (1f - clamp(p.open * 2f));

        for (int py = 0; py < SIZE; py++) {
            float y = 1f - (py + 0.5f) * PX;
            float shift = 0f;
            if (p.glitch > 0f) {
                int h = hash(p.seed * 31 + py / 6);
                if ((h & 0xFF) < p.glitch * 90f) shift = ((h >>> 8 & 0xFF) / 255f - 0.5f) * p.glitch * 0.5f;
            }
            for (int px = 0; px < SIZE; px++) {
                float x = (px + 0.5f) * PX - 1f + shift;
                float ax = Math.abs(x);
                float ink = 0f, white = 0f, heart = 0f, tear = 0f;

                // ---- eyes
                boolean right = x > 0;
                float ex = ax - ecx - (x < 0 ? -look : look), ey = y - ecy;
                float shut = Math.max(p.arches, right ? clamp((p.wink - 0.5f) * 2.5f) : 0f);
                float ry = eyeRy * Math.max(p.lid * (right ? 1f - 0.93f * clamp(p.wink * 2f) : 1f), 0.07f);
                if (p.heart > 0.5f && Math.abs(ex) < 0.16f && Math.abs(ey) < 0.16f) {
                    float hx = ex / 0.105f, hy = (ey + 0.02f) / 0.105f;              // (x² + y² - 1)³ - x²y³ <= 0: a heart
                    float q = hx * hx + hy * hy - 1f;
                    if (q * q * q - hx * hx * hy * hy * hy <= 0f) heart = 1f;
                } else if (shut > 0.5f && Math.abs(ex) < p.eyeRx * 1.6f && Math.abs(ey) < p.eyeRy + 0.03f) {
                    float s = ex / (p.eyeRx * 1.45f);                               // a smiling arch: ^
                    float top = -0.02f + p.eyeRy * 0.5f * (1f - s * s);
                    float slope = p.eyeRy * 0.5f * 2f * s / (p.eyeRx * 1.45f);
                    float d = Math.abs(s) <= 1f ? Math.abs(ey - top) / (float) Math.sqrt(1f + slope * slope)
                            : (float) Math.hypot(Math.abs(ex) - p.eyeRx * 1.45f, ey + 0.02f);
                    ink = cover(d - p.thick * 1.15f);
                } else if (Math.abs(ex) < p.eyeRx + 0.03f && Math.abs(ey) < ry + 0.03f) {
                    float d = ((float) Math.sqrt(ex * ex / (p.eyeRx * p.eyeRx) + ey * ey / (ry * ry)) - 1f) * Math.min(p.eyeRx, ry);
                    if (p.brow != 0f) {
                        float s = ex / p.eyeRx;                                   // -1 inner edge ... +1 outer edge
                        float a = Math.abs(p.brow);
                        float cut = ry * (1.25f - 1.35f * a) + Math.signum(p.brow) * a * ry * 0.7f * s;
                        d = Math.max(d, ey - cut);
                    }
                    ink = cover(d);
                    if (p.pupil > 0f && ink > 0f) {
                        float qx = ex - p.lookX * p.eyeRx * 0.3f, qy = ey - p.lookY * ry * 0.3f;
                        white = cover((float) Math.sqrt(qx * qx + qy * qy) - p.pupil * p.eyeRx * 0.6f) * ink;
                    }
                }
                if (p.tears > 0.05f) {                                            // a drop that runs down from each eye
                    float tx = ax - ecx - 0.01f, ty = y - (ecy - p.eyeRy - 0.05f - p.tearPhase * 0.22f);
                    float d = (float) Math.sqrt(tx * tx / (0.036f * 0.036f) + ty * ty / (0.055f * 0.055f)) - 1f;
                    if (ty > 0) d = Math.max(d, Math.abs(tx) - 0.036f * (1f - ty / 0.08f));
                    tear = Math.max(tear, cover(d * 0.04f) * p.tears * (1f - p.tearPhase * 0.4f));
                }

                // ---- mouth
                if (ax < mw + 0.08f) {
                    float yy = y;
                    y = y + p.wave * 0.022f * (float) Math.sin(x * 48f + p.tearPhase * 12f) - p.tilt * x * 0.35f;   // a trembling mouth, a crooked one
                    float t = Math.min(ax / mw, 1f), bow = 1f - t * t;
                    float yTop = p.mouthY - cTop * bow, yBot = p.mouthY - cBot * bow;
                    float dTop, dBot;
                    if (ax <= mw) {
                        dTop = Math.abs(y - yTop) / (float) Math.sqrt(1f + sq(2f * cTop * t / mw));
                        dBot = Math.abs(y - yBot) / (float) Math.sqrt(1f + sq(2f * cBot * t / mw));
                    } else {
                        dTop = dBot = (float) Math.hypot(ax - mw, y - p.mouthY);
                    }
                    float stroke = cover(Math.min(dTop, dBot) - p.thick);
                    float lo = Math.min(yTop, yBot), hi = Math.max(yTop, yBot);
                    boolean inside = ax <= mw && y > lo && y < hi;
                    if (inside && p.teeth > 0.01f) {
                        float edge = Math.min(y - lo, hi - y);
                        float gum = cover(p.thick * 1.6f - edge);                 // dark rim along the lips
                        float gapV = cover(Math.abs((ax + 0.0425f) % 0.085f - 0.0425f) - 0.006f);
                        float gapH = cover(Math.abs(y - (lo + hi) * 0.5f) - 0.006f);
                        float dark = Math.max(gum, Math.max(gapV, gapH));
                        white = Math.max(white, (1f - dark) * p.teeth);
                        ink = Math.max(ink, Math.max(dark, 1f - p.teeth));
                    } else if (inside) {
                        ink = 1f;
                    }
                    ink = Math.max(ink, stroke);
                    if (dimple > 0f && ax > mw - 0.01f) {                         // the little ticks at the ends of the smile
                        float d = segment(ax, y, mw, p.mouthY, mw + 0.028f, p.mouthY + 0.04f) - p.thick * 0.85f;
                        ink = Math.max(ink, cover(d) * dimple);
                    }
                    y = yy;
                }

                if (heart > 0f) { img.setPixelRGBA(px, py, 0xFF000000 | 0x1E << 16 | 0x18 << 8 | 0xC8); continue; }      // red, ABGR
                if (tear > 0.02f && ink <= 0f) { img.setPixelRGBA(px, py, (int) (tear * 230f) << 24 | 0xFF << 16 | 0xC8 << 8 | 0x78); continue; }
                if (ink <= 0f) continue;
                int r, g, b;
                if (white > 0f) {
                    r = lerp(INK >> 16 & 0xFF, 245, white); g = lerp(INK >> 8 & 0xFF, 240, white); b = lerp(INK & 0xFF, 225, white);
                } else {
                    r = INK >> 16 & 0xFF; g = INK >> 8 & 0xFF; b = INK & 0xFF;
                }
                img.setPixelRGBA(px, py, (int) (ink * 255f) << 24 | b << 16 | g << 8 | r);
            }
        }
        texture.upload();
    }

    private static float cover(float d) { return clamp(0.5f - d / PX); }
    private static float clamp(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
    private static float sq(float v) { return v * v; }
    private static int lerp(int a, int b, float t) { return (int) (a + (b - a) * t); }

    private static float segment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float t = clamp(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy));
        return (float) Math.hypot(px - ax - dx * t, py - ay - dy * t);
    }

    private static int hash(int h) {
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; h ^= h >>> 16;
        return h;
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
    }
}
