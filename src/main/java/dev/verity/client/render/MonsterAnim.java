package dev.verity.client.render;

import dev.verity.client.mesh.Skeleton;
import dev.verity.client.voice.Voice;
import dev.verity.entity.VerityMonster;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;

/**
 * Procedural animation for the monster: nothing is keyframed, every bone angle is computed from how fast it
 * moves, what it looks at, how much room there is above it and what it is doing.
 *
 * It is meant to look wrong. The pose is not updated every frame but about a dozen times a second with the odd
 * dropped frame, like a damaged recording; and on every one of those steps a few joints are re-rolled into
 * positions joints should not take: elbows bent backwards, arms crossed over the chest, the spine wrung like a
 * cloth, the head hanging upside down for a moment. Nothing eases in or out; it snaps.
 *
 * One number, "lowness", folds the 6.5 m body to fit the space it is in: 0 = standing tall, 1 = a deep crouch
 * (about 5 blocks), 2 = on all fours (about 3.3), 3 = flat to the floor (about 2). Each step the smallest
 * lowness whose pose clears the ceiling is found by bisection.
 */
public final class MonsterAnim {
    public static final float SCALE = 6.5f / 1.9f;          // model is 1.9 units tall, he is 6.5 m
    private static final float GROUND = -0.95f;
    private static final int KINKS = 24;
    private static final Map<VerityMonster, MonsterAnim> ALL = new WeakHashMap<>();

    public static MonsterAnim of(VerityMonster m) {
        return ALL.computeIfAbsent(m, k -> new MonsterAnim());
    }

    private final Random rng = new Random();
    private final Vector3f tmp = new Vector3f();
    private long lastNanos;
    private double time;
    private float stepTimer, lowness, reach, jaw, scream, calm, rise = 1f, twitch;
    private int headroomTick = -100;
    private float headroom = 8f;

    // the held pose inputs: only refreshed on a step
    private float amount, sw, cw, armSw, breathe, lean, yaw, pitch, pReach, pScream, pCalm, chaos, poseTime;
    private boolean hunting;
    /** Random joint offsets in [-1, 1]; a few are re-rolled on every step. */
    private final float[] kink = new float[KINKS];
    /** -1, 0 or 1: the head rolled right over to one side. */
    private int flip;

    /** Results for the renderer. */
    public float jitter, scale = 1f, glint = 0.5f;
    public int seed;

    private int root, spine1, spine2, neck, head, jawBone;
    private final int[] arm = new int[2], forearm = new int[2], hand = new int[2], fingers = new int[2], thigh = new int[2], shin = new int[2], foot = new int[2];
    private boolean bound;

    private void bind(Skeleton s) {
        root = s.mesh.bone("root"); spine1 = s.mesh.bone("spine1"); spine2 = s.mesh.bone("spine2");
        neck = s.mesh.bone("neck"); head = s.mesh.bone("head"); jawBone = s.mesh.bone("jaw");
        String[] side = {"l_", "r_"};
        for (int i = 0; i < 2; i++) {
            arm[i] = s.mesh.bone(side[i] + "arm"); forearm[i] = s.mesh.bone(side[i] + "forearm"); hand[i] = s.mesh.bone(side[i] + "hand");
            fingers[i] = s.mesh.bone(side[i] + "fingers"); thigh[i] = s.mesh.bone(side[i] + "thigh"); shin[i] = s.mesh.bone(side[i] + "shin");
            foot[i] = s.mesh.bone(side[i] + "foot");
        }
        bound = true;
    }

    public void apply(VerityMonster m, Skeleton s, float partialTick, float headYaw, float headPitch) {
        if (!bound) bind(s);
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min((now - lastNanos) / 1e9f, 0.1f);
        lastNanos = now;
        time += dt;
        int state = m.getState();
        float stateTime = (m.getStateTicks() + partialTick) / 20f;
        hunting = state == VerityMonster.STATE_HUNT;

        // --- things that follow the game every frame
        Player target = m.level().getNearestPlayer(m, 48);
        float dist = target == null ? 99f : m.distanceTo(target);
        reach += ((hunting ? Mth.clamp((13f - dist) / 8f, 0f, 1f) : 0f) - reach) * Math.min(1f, dt * 6f);
        scream += ((state == VerityMonster.STATE_SCREAM ? 1f : 0f) - scream) * Math.min(1f, dt * 14f);
        calm += ((state == VerityMonster.STATE_CALM ? 1f : 0f) - calm) * Math.min(1f, dt * 2.5f);
        float voice = Voice.level(m.getId());
        float jawTarget = Math.max(Math.max(voice * 0.85f, scream), hunting ? 0.25f + reach * (0.35f + 0.25f * (float) Math.sin(time * 9)) : 0.05f);
        jaw += (jawTarget - jaw) * Math.min(1f, dt * 30f);
        twitch = Math.max(0f, twitch - dt * 9f);

        // unfolding out of the ball, folding back into it
        rise = state == VerityMonster.STATE_RISE ? Mth.clamp(stateTime / 2.2f, 0f, 1f) : Math.min(1f, rise + dt);
        float fold = 1f - rise * rise * (3f - 2f * rise);
        scale = 0.12f + 0.88f * (1f - fold * fold);
        if (state == VerityMonster.STATE_CALM) scale *= 1f - 0.75f * Mth.clamp((stateTime - 2.5f) / 1.5f, 0f, 1f);
        glint = state == VerityMonster.STATE_CALM ? 0.25f : hunting ? 0.75f + 0.25f * (float) Math.sin(time * 23) : 0.55f;

        // --- the step: a new pose a dozen times a second, sometimes a frame is simply missing
        stepTimer -= dt;
        boolean step = stepTimer <= 0f;
        if (step) {
            boolean frantic = hunting || scream > 0.3f || fold > 0.05f;
            stepTimer = (frantic ? 0.065f : 0.1f) + (rng.nextInt(6) == 0 ? 0.11f + rng.nextFloat() * 0.1f : 0f);
            amount = Math.min(m.walkAnimation.speed(partialTick), 1f);
            float phase = m.walkAnimation.position(partialTick) * 0.58f;
            sw = Mth.sin(phase); cw = Mth.cos(phase);
            armSw = Mth.sin(phase * 1.6f + 1.1f);                // the arms keep their own, wrong, rhythm
            breathe = (float) Math.sin(time * 1.4);
            lean = 5f + 11f * amount + (hunting ? 10f : 0f);
            yaw = Mth.clamp(headYaw, -85f, 85f);
            pitch = Mth.clamp(headPitch, -55f, 55f) * (1f - calm);
            pReach = reach; pScream = scream; pCalm = calm;
            poseTime = (float) time;
            chaos = (1f - calm) * (frantic ? 1f : 0.45f) * (1f + 0.4f * scream);

            // wrench a few joints somewhere new
            int rolls = frantic ? 5 : rng.nextInt(3) == 0 ? 3 : 0;
            for (int i = 0; i < rolls; i++) kink[rng.nextInt(KINKS)] = rng.nextFloat() * 2f - 1f;
            if (rolls > 0) { twitch = 1f; seed = rng.nextInt(); }
            if (rng.nextInt(frantic ? 10 : 45) == 0) flip = rng.nextBoolean() ? 1 : -1;
            else if (flip != 0 && rng.nextInt(3) == 0) flip = 0;

            if (m.tickCount - headroomTick >= 3) {
                headroomTick = m.tickCount;
                headroom = measureHeadroom(m);
            }
            float room = state == VerityMonster.STATE_PEER ? Math.min(headroom, 3.4f) : headroom;       // peering: down on all fours even in the open
            float need = (room - 0.12f) / (SCALE * Math.max(scale, 0.2f));
            float want;
            if (pose(s, 0f) <= need) {
                want = 0f;
            } else if (pose(s, 3f) > need) {
                want = 3f;
            } else {
                float lo = 0f, hi = 3f;
                for (int i = 0; i < 7; i++) {
                    float mid = (lo + hi) * 0.5f;
                    if (pose(s, mid) <= need) hi = mid; else lo = mid;
                }
                want = hi;
            }
            want = Math.max(want, fold + calm * 0.6f);          // also crouched while unfolding or kneeling down
            lowness += (want - lowness) * (want > lowness ? 0.75f : 0.4f);
        }
        jitter = Math.max(fold * 0.05f, twitch * 0.014f * chaos) + (state == VerityMonster.STATE_CALM ? 0.02f * calm : 0f)
                + (hunting ? 0.003f : 0f);
        pose(s, lowness);
    }

    /**
     * Sets every bone for the given lowness, plants the lowest limb on the ground and solves the skeleton.
     * @return the height of the pose in model units
     */
    private float pose(Skeleton s, float u) {
        float c = Mth.clamp(u, 0f, 1f);                     // crouch
        float k = Mth.clamp(u - 1f, 0f, 1f);                // all fours
        float flat = Mth.clamp(u - 2f, 0f, 1f);             // pressed to the floor
        float up = 1f - k;                                  // how much of the upright pose remains
        float ch = chaos, t = poseTime;

        s.reset();
        s.rot(root, (82f + 4f * flat) * k + 14f * pCalm * up, 5f * sw * amount + kink[20] * 10f * ch, kink[21] * 6f * ch);
        s.rot(spine1, (lean + 28f * c + 14f * pCalm - 10f * pScream) * up - 2f * k + kink[0] * 12f * ch, kink[1] * 22f * ch,
                4f * sw * amount + kink[2] * 14f * ch);
        s.rot(spine2, (lean * 0.8f + 26f * c + 16f * pCalm - 14f * pScream) * up - 10f * k + breathe * 1.3f + kink[3] * 12f * ch,
                -9f * sw * amount + kink[4] * 34f * ch, kink[5] * 16f * ch);
        float spineSum = (lean * 1.8f + 54f * c + 44f * pCalm - 24f * pScream) * up + (70f - 26f * flat) * k;
        // the head: locked on you, but canted hard to one side, and now and then rolled clean over
        float roll = kink[6] * 48f * ch + flip * 150f * Math.min(ch, 1f);
        s.rot(neck, pitch * 0.4f * up - spineSum * 0.45f + 14f * pScream, yaw * 0.4f + kink[7] * 18f * ch, roll * 0.35f);
        s.rot(head, pitch * 0.6f * up - spineSum * 0.45f + 22f * pCalm * up - 12f * pScream + twitch * 6f, yaw * 0.6f, roll * 0.65f);
        s.rot(jawBone, jaw * 44f, 0f, kink[22] * 5f * ch);

        for (int i = 0; i < 2; i++) {
            float sgn = i == 0 ? 1f : -1f;                  // the left leg leads on sin > 0
            float swing = sgn * sw, lift = Math.max(0f, sgn * cw);
            // legs: long, hard strides; the knees buckle and the thighs splay at random
            float hip = (-46f * amount * swing - 58f * c + kink[8 + i] * 12f * ch * (0.4f + amount)) * up
                    - (150f + 10f * flat + 16f * swing * amount) * k;
            float knee = ((70f * lift + 8f) * amount + 98f * c + Math.max(0f, kink[10 + i]) * 30f * ch) * up
                    + (128f + 22f * flat + 14f * lift * amount) * k;
            s.rot(thigh[i], hip, kink[12 + i] * 14f * ch * up, sgn * (2f + 10f * k) + kink[12 + i] * 10f * ch);
            s.rot(shin[i], knee, 0f, 0f);
            s.rot(foot[i], -(hip + knee) * 0.35f * up - 20f * k + kink[10 + i] * 18f * ch, 0f, 0f);

            // arms: out of step with the legs, thrown across the body, elbows folding the wrong way
            float armX = 34f * amount * sgn * armSw - 16f * c + 4f * breathe + kink[14 + i] * 38f * ch;
            float armY = kink[16 + i] * 60f * ch;           // pulled across the chest or wrenched behind the back
            float armZ = sgn * (3f + 5f * c) + kink[18 + i] * 26f * ch;
            float bend = kink[i == 0 ? 23 : 20];
            float elbow = bend > 0.35f ? 75f * bend * ch : -12f - 18f * amount - 22f * c + bend * 40f * ch;      // > 0 = hyperextended
            float wristY = kink[i == 0 ? 21 : 22] * 85f * ch;
            float curl = -14f + 14f * (float) Math.sin(t * (9.0 + i * 2) + i * 2.1) + kink[14 + i] * 30f * ch;
            float r = pReach * up;
            armX = Mth.lerp(r, armX, -88f + 14f * (float) Math.sin(t * 13.3 + i * 1.7) + kink[14 + i] * 22f * ch);
            armZ = Mth.lerp(r, armZ, sgn * -6f + kink[18 + i] * 18f * ch);
            armY = Mth.lerp(r, armY, kink[16 + i] * 30f * ch);
            elbow = Mth.lerp(r, elbow, -24f - 16f * (float) Math.sin(t * 17.1 + i) + (bend > 0.6f ? 60f * ch : 0f));
            curl = Mth.lerp(r, curl, -52f + 30f * (float) Math.sin(t * 21 + i * 3));
            // on all fours the arms reach out ahead and drag him along
            armX = Mth.lerp(k, armX, -137f + (20f * swing + 6f * pReach) * Math.max(amount, 0.2f) + kink[14 + i] * 16f * ch);
            elbow = Mth.lerp(k, elbow, -43f + 16f * lift * amount + bend * 22f * ch);
            armZ = Mth.lerp(k, armZ, sgn * 11f + kink[18 + i] * 14f * ch);
            armY = Mth.lerp(k, armY, kink[16 + i] * 22f * ch);
            armX = Mth.lerp(pScream * up, armX, 28f + kink[14 + i] * 30f);
            armZ = Mth.lerp(pScream * up, armZ, sgn * 38f + kink[18 + i] * 24f);
            curl = Mth.lerp(pScream, curl, 18f);
            s.rot(arm[i], armX, armY, armZ);
            s.rot(forearm[i], elbow, 0f, kink[18 + i] * 12f * ch);
            s.rot(hand[i], -10f * r, wristY, 0f);
            s.rot(fingers[i], curl, 0f, sgn * kink[16 + i] * 14f * ch);
        }

        // plant whatever hangs lowest on the ground, then see how tall the result is
        s.solve();
        float lowest = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            float sx = i == 0 ? 1f : -1f;
            lowest = Math.min(lowest, s.point(foot[i], sx * 0.118f, -0.948f, -0.06f, tmp).y);       // heel
            lowest = Math.min(lowest, s.point(foot[i], sx * 0.140f, -0.948f, 0.10f, tmp).y);        // toes
            if (k > 0.02f) {
                lowest = Math.min(lowest, s.point(fingers[i], sx * 0.25f, -0.57f, -0.01f, tmp).y);  // fingertips
                lowest = Math.min(lowest, s.head(forearm[i], tmp).y - 0.03f);                       // elbows
                lowest = Math.min(lowest, s.head(shin[i], tmp).y - 0.04f);                          // knees
            }
            top = Math.max(top, s.head(shin[i], tmp).y + 0.04f);
            top = Math.max(top, s.head(arm[i], tmp).y + 0.05f);
        }
        top = Math.max(top, s.point(head, 0f, 0.955f, 0.01f, tmp).y);                               // crown
        top = Math.max(top, s.point(head, 0f, 0.86f, -0.095f, tmp).y);                              // back of the skull
        top = Math.max(top, s.point(head, 0f, 0.84f, 0.12f, tmp).y);                                // face
        top = Math.max(top, s.point(spine2, 0f, 0.55f, -0.125f, tmp).y);                            // shoulder blades
        top = Math.max(top, s.point(root, 0f, 0.10f, -0.10f, tmp).y);                               // tailbone
        s.rootOffset.y = GROUND - lowest;
        s.solve();
        return top - lowest;
    }

    /** Free height above the monster's feet, in blocks (capped at 8). */
    private static float measureHeadroom(VerityMonster m) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int x = Mth.floor(m.getX()), z = Mth.floor(m.getZ()), y0 = Mth.floor(m.getY() + 0.2);
        for (int i = 0; i < 8; i++) {
            p.set(x, y0 + i, z);
            if (!m.level().getBlockState(p).getCollisionShape(m.level(), p).isEmpty()) return i;
        }
        return 8f;
    }
}
