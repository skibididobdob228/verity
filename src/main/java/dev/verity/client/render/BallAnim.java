package dev.verity.client.render;

import dev.verity.client.voice.Voice;
import dev.verity.entity.Emotion;
import dev.verity.entity.VerityBall;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;

/**
 * Client-side animation state of one Verity: face, blinking, lip-sync, squash and stretch, glitches.
 * Balls lying in the world each have their own; the item in a hand or inventory slot shares {@link #item()},
 * since there is only one Verity.
 */
public final class BallAnim {
    private static final Map<VerityBall, BallAnim> ALL = new WeakHashMap<>();

    public static BallAnim of(VerityBall ball) {
        if (ALL.size() > 8) {
            for (Iterator<Map.Entry<VerityBall, BallAnim>> it = ALL.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<VerityBall, BallAnim> e = it.next();
                if (e.getKey().isRemoved()) {
                    e.getValue().faceTexture.close();
                    it.remove();
                }
            }
        }
        return ALL.computeIfAbsent(ball, b -> new BallAnim());
    }

    private static BallAnim item;

    /** The face of Verity-the-item, wherever it is drawn (hand, hotbar, another player's hand). */
    public static BallAnim item() {
        if (item == null) item = new BallAnim();
        return item;
    }

    public final FaceTexture faceTexture = new FaceTexture();
    private final FaceParams face = new FaceParams();
    private final FaceParams shown = new FaceParams();
    private final Random rng = new Random();

    private long lastNanos;
    private double time;
    private float blink, talk, flash, saccadeX, saccadeY;
    private double nextBlink = 2, nextSaccade = 1;
    private Emotion flashFace = Emotion.TEETH, idleFace = null;
    private double idleUntil, nextIdle = 8;

    /** Results for the renderer. */
    public float bob, roll, scaleY = 1f, scaleXZ = 1f, jitter;
    public int tint = 0xFFFFFFFF, seed;

    public void update(VerityBall ball, float partialTick) {
        boolean held = ball.getMode() == VerityBall.MODE_STAY;
        update(ball.getEmotion(), ball.getCorruption(), Voice.level(ball.getId()), ball.getY() - ball.yo,
                Math.hypot(ball.getX() - ball.xo, ball.getZ() - ball.zo), held, ball.getMode() == VerityBall.MODE_TRANSFORM);
    }

    /**
     * @param hovering   true only for the showcase "anchor" mode, where he floats; otherwise he rests on something
     * @param struggling true while he is about to change: he shakes
     */
    public void update(Emotion emotion, float c, float level, double vy, double vh, boolean hovering, boolean struggling) {
        // to a mind that is going he looks further gone than he is
        c = Math.max(c, dev.verity.client.fx.Sanity.corruption());
        emotion = dev.verity.client.fx.Sanity.face(emotion);
        long now = System.nanoTime();
        if (lastNanos != 0 && now - lastNanos < 1_500_000L) return;        // already updated this frame (the item is drawn several times)
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1e9f, 0.1f);
        lastNanos = now;
        time += dt;

        // voice: quick attack, softer release
        talk += (level - talk) * (level > talk ? Math.min(1f, dt * 40f) : Math.min(1f, dt * 16f));

        // blinking (he stops blinking as he comes apart)
        boolean stares = emotion == Emotion.CREEPY || emotion == Emotion.TEETH;
        if (time > nextBlink && !stares) {
            blink = 1f;
            nextBlink = time + 1.6 + rng.nextDouble() * 4.5 + c * 6;
        }
        blink = Math.max(0f, blink - dt * 7.5f);
        if (time > nextSaccade) {
            saccadeX = (rng.nextFloat() - 0.5f) * 1.6f; saccadeY = (rng.nextFloat() - 0.5f) * 1.2f;
            nextSaccade = time + 0.8 + rng.nextDouble() * 3;
            if (rng.nextFloat() < 0.6f) { saccadeX = 0; saccadeY = 0; }
        }

        // corruption: for a few frames the mask slips
        if (flash > 0f) {
            flash -= dt;
        } else if (c > 0.05f && rng.nextFloat() < dt * c * c * 2.2f) {
            flash = 0.06f + rng.nextFloat() * 0.22f * (0.4f + c);
            flashFace = rng.nextBoolean() ? Emotion.TEETH : Emotion.CREEPY;
            seed = rng.nextInt();
        }
        boolean flashing = flash > 0f;

        // a living face is never still for long: between words he winks, smirks, nods off, laughs at nothing
        if (time > nextIdle && talk < 0.05f && (emotion == Emotion.HAPPY || emotion == Emotion.GRIN || emotion == Emotion.NEUTRAL)) {
            Emotion[] pool = emotion == Emotion.NEUTRAL ? new Emotion[] {Emotion.BLANK, Emotion.SLEEPY, Emotion.SMUG}
                    : new Emotion[] {Emotion.WINK, Emotion.SMUG, Emotion.LAUGH, Emotion.SLEEPY, Emotion.LOVE, Emotion.GRIN};
            idleFace = pool[rng.nextInt(pool.length)];
            idleUntil = time + 0.9 + rng.nextDouble() * 1.4;
            nextIdle = time + 7 + rng.nextDouble() * 12;
        }
        Emotion shownFace = idleFace != null && time < idleUntil && talk < 0.05f ? idleFace : emotion;

        FaceParams target = FaceParams.of(flashing ? flashFace : shownFace);
        target.lookX += saccadeX; target.lookY += saccadeY;
        if (flashing) face.set(target); else face.approach(target, 1f - (float) Math.exp(-dt * 13f));

        shown.set(face);
        shown.tearPhase = face.tears > 0.01f || face.wave > 0.01f ? (float) (time * 0.55 % 1.0) : 0f;     // only moving faces are redrawn every frame
        float blinkShape = blink > 0.5f ? (1f - blink) * 2f : blink * 2f;      // close fast, open slower
        shown.eyeOpen = 1f - blinkShape;
        shown.open = Math.max(face.open, talk * 0.85f);
        shown.curve = face.curve * (1f - 0.22f * talk);
        shown.mouthW = face.mouthW * (1f - 0.10f * talk);
        shown.glitch = flashing ? 1f : Math.max(0f, c - 0.35f) * 0.6f;
        shown.seed = flashing ? seed : (int) (time * 12) * (c > 0.35f ? 1 : 0);
        faceTexture.draw(shown);

        // body
        bob = hovering ? (float) (Math.sin(time * 2.1) * 0.035 + Math.sin(time * 1.31 + 1.0) * 0.015) : 0f;
        float stretch = Mth.clamp((float) vy * 0.9f, -0.22f, 0.28f) + talk * 0.05f * (float) Math.sin(time * 31)
                + (hovering ? 0f : 0.012f * (float) Math.sin(time * 2.3));                                    // resting: he breathes
        scaleY += (1f + stretch - scaleY) * Math.min(1f, dt * 18f);
        scaleXZ = 1f / (float) Math.sqrt(Math.max(scaleY, 0.3f));
        roll = (float) (Math.sin(time * 1.7) * (hovering ? 3.0 : 1.2) + Math.min(vh, 0.6) * 22.0 * Math.sin(time * 5.0))
                + (struggling ? (rng.nextFloat() - 0.5f) * 30f : 0f);
        jitter = flashing ? 0.10f : struggling ? 0.05f : 0f;

        float k = c * 0.75f;
        int r = (int) Mth.lerp(k, 255, 200), g = (int) Mth.lerp(k, 255, 186), b = (int) Mth.lerp(k, 255, 150);
        if (flashing && rng.nextBoolean()) { r = 150; g = 140; b = 96; }
        tint = 0xFF000000 | r << 16 | g << 8 | b;
    }
}
