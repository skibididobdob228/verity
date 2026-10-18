package dev.verity.client.render;

import dev.verity.entity.Emotion;

/**
 * Everything that defines Verity's drawn-on face. Coordinates are in "face space": x right, y up, both in
 * [-1, 1], where 1 is {@link FaceTexture#HALF_ANGLE_DEG} of arc on the ball. The HAPPY preset reproduces
 * the smiley painted on the original model.
 */
public final class FaceParams {
    public float eyeX = 0.20f, eyeY = 0.17f, eyeRx = 0.054f, eyeRy = 0.138f;
    /** 1 = open, 0 = shut. */
    public float eyeOpen = 1f;
    /** -1 sad (outer corners drop) ... +1 angry (inner corners drop). */
    public float brow = 0f;
    public float lookX = 0f, lookY = 0f;
    /** Size of the pinprick of light in each eye, 0 = none. */
    public float pupil = 0f;
    public float mouthW = 0.51f, mouthY = -0.07f;
    /** Depth of the smile; negative for a frown. */
    public float curve = 0.29f;
    public float open = 0f;
    public float thick = 0.024f;
    /** 0 = black mouth, 1 = full of teeth. */
    public float teeth = 0f;
    public float glitch = 0f;
    public int seed = 0;
    /** How far the lids are down whatever the blinking does (1 = wide open, 0.3 = half asleep). */
    public float lid = 1f;
    /** 1 = the right eye shut in a wink. */
    public float wink = 0f;
    /** 1 = both eyes drawn as smiling arches, shut with laughter. */
    public float arches = 0f;
    /** 1 = the eyes are hearts. */
    public float heart = 0f;
    /** 1 = tears running from both eyes; tearPhase (0..1) is how far down they have run. */
    public float tears = 0f, tearPhase = 0f;
    /** A trembling, wavy mouth (fear, crying). */
    public float wave = 0f;
    /** One corner of the mouth higher than the other: the smirk. */
    public float tilt = 0f;

    public static FaceParams of(Emotion e) {
        FaceParams p = new FaceParams();
        switch (e) {
            case HAPPY -> {}
            case GRIN -> { p.mouthW = 0.55f; p.curve = 0.33f; p.open = 0.42f; p.eyeRy = 0.125f; }
            case NEUTRAL -> { p.mouthW = 0.34f; p.mouthY = -0.24f; p.curve = 0f; }
            case SAD -> { p.mouthW = 0.34f; p.mouthY = -0.34f; p.curve = -0.13f; p.brow = -0.85f; p.lookY = -0.6f; }
            case ANGRY -> { p.mouthW = 0.40f; p.mouthY = -0.33f; p.curve = -0.12f; p.brow = 1f; p.eyeRy = 0.125f; }
            case SURPRISED -> { p.mouthW = 0.13f; p.mouthY = -0.20f; p.curve = 0.05f; p.open = 0.5f; p.eyeRx = 0.062f; p.eyeRy = 0.155f; }
            case CREEPY -> { p.mouthW = 0.68f; p.mouthY = 0.0f; p.curve = 0.42f; p.eyeRx = 0.064f; p.eyeRy = 0.15f; p.pupil = 0.42f; }
            case TEETH -> { p.mouthW = 0.68f; p.mouthY = 0.0f; p.curve = 0.36f; p.open = 0.55f; p.teeth = 1f; p.eyeRx = 0.075f; p.eyeRy = 0.1f; p.pupil = 0.25f; }
            case WINK -> { p.wink = 1f; p.curve = 0.31f; p.tilt = 0.35f; }
            case LAUGH -> { p.arches = 1f; p.mouthW = 0.50f; p.mouthY = -0.06f; p.curve = 0.30f; p.open = 0.62f; }
            case SMUG -> { p.lid = 0.5f; p.brow = 0.25f; p.lookX = 0.7f; p.mouthW = 0.34f; p.mouthY = -0.16f; p.curve = 0.14f; p.tilt = 0.8f; }
            case SCARED -> { p.eyeRx = 0.07f; p.eyeRy = 0.16f; p.pupil = 0.55f; p.brow = -0.55f; p.mouthW = 0.30f; p.mouthY = -0.28f; p.curve = -0.04f; p.open = 0.16f; p.wave = 1f; }
            case CRYING -> { p.lid = 0.45f; p.brow = -1f; p.tears = 1f; p.mouthW = 0.30f; p.mouthY = -0.32f; p.curve = -0.18f; p.open = 0.28f; p.wave = 0.5f; p.lookY = -0.5f; }
            case LOVE -> { p.heart = 1f; p.curve = 0.33f; p.mouthW = 0.48f; }
            case BLANK -> { p.eyeRx = 0.028f; p.eyeRy = 0.03f; p.mouthW = 0.30f; p.mouthY = -0.20f; p.curve = 0f; p.thick = 0.012f; }
            case SLEEPY -> { p.lid = 0.3f; p.lookY = -0.4f; p.mouthW = 0.18f; p.mouthY = -0.22f; p.curve = 0.04f; p.open = 0.1f; }
        }
        return p;
    }

    /** Exponential approach towards {@code t}; {@code k} in (0, 1]. */
    public void approach(FaceParams t, float k) {
        eyeX += (t.eyeX - eyeX) * k; eyeY += (t.eyeY - eyeY) * k; eyeRx += (t.eyeRx - eyeRx) * k; eyeRy += (t.eyeRy - eyeRy) * k;
        brow += (t.brow - brow) * k; lookX += (t.lookX - lookX) * k; lookY += (t.lookY - lookY) * k; pupil += (t.pupil - pupil) * k;
        mouthW += (t.mouthW - mouthW) * k; mouthY += (t.mouthY - mouthY) * k; curve += (t.curve - curve) * k; open += (t.open - open) * k;
        thick += (t.thick - thick) * k; teeth += (t.teeth - teeth) * k;
        lid += (t.lid - lid) * k; wink += (t.wink - wink) * k; arches += (t.arches - arches) * k; heart += (t.heart - heart) * k;
        tears += (t.tears - tears) * k; wave += (t.wave - wave) * k; tilt += (t.tilt - tilt) * k;
    }

    public void set(FaceParams o) {
        eyeX = o.eyeX; eyeY = o.eyeY; eyeRx = o.eyeRx; eyeRy = o.eyeRy; eyeOpen = o.eyeOpen; brow = o.brow; lookX = o.lookX; lookY = o.lookY;
        pupil = o.pupil; mouthW = o.mouthW; mouthY = o.mouthY; curve = o.curve; open = o.open; thick = o.thick; teeth = o.teeth;
        glitch = o.glitch; seed = o.seed;
        lid = o.lid; wink = o.wink; arches = o.arches; heart = o.heart; tears = o.tears; tearPhase = o.tearPhase; wave = o.wave; tilt = o.tilt;
    }

    /** Cheap fingerprint so the texture is only redrawn when something visibly changed. */
    public int fingerprint() {
        int h = 17;
        for (float f : new float[] {eyeX, eyeY, eyeRx, eyeRy, eyeOpen, brow, lookX, lookY, pupil, mouthW, mouthY, curve, open, thick, teeth, glitch,
                lid, wink, arches, heart, tears, tearPhase, wave, tilt}) {
            h = h * 31 + Math.round(f * 400f);
        }
        return h * 31 + seed;
    }
}
