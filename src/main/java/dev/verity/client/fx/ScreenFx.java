package dev.verity.client.fx;

import dev.verity.VerityConfig;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.net.StatePayload;
import dev.verity.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Everything that happens to the player's screen and ears rather than in the world: camera shake, fog that
 * closes in, the heartbeat when he is near, the old tune, and the smoke that hangs around the monster (the ball has no particles).
 */
public final class ScreenFx {
    // one-shot effects (set by packets), read by the HUD
    public static float shake, staticLevel, glitch, flash;
    public static int shakeTicks, staticTicks, glitchTicks, jumpscare, blackout, blackoutTotal;
    // slow state
    public static int stage, daysLeft = -1, emotion, carrierId = -1;
    public static float corruption;
    public static float darkness, dread, proximity;
    /** 0 by day, 1 in the dead of night (already scaled by the config). */
    public static float night;
    /** 0 in the open dark, 1 next to a light source or with a torch in hand: how much the night backs off. */
    public static float lightRelief;
    private static float targetDarkness, targetDread;
    private static boolean music;
    private static SoundInstance tune, drone;
    private static int heartTimer, tick;

    /** The old tune of the night he comes is playing. */
    public static boolean huntTune() { return music; }

    public static void setState(StatePayload s) {
        stage = s.stage(); daysLeft = s.daysLeft(); targetDarkness = s.darkness(); targetDread = s.dread(); music = s.music();
        emotion = s.emotion(); corruption = s.corruption(); carrierId = s.carrierId();
        Sanity.set(s.sanity());
    }

    public static void trigger(int kind, float amount, int ticks) {
        float k = VerityConfig.FX_INTENSITY.get().floatValue();
        switch (kind) {
            case FxPayload.SHAKE -> { shake = Math.max(shake, amount * k); shakeTicks = Math.max(shakeTicks, ticks); }
            case FxPayload.STATIC -> { staticLevel = Math.max(staticLevel, amount * k); staticTicks = Math.max(staticTicks, ticks); }
            case FxPayload.GLITCH -> { glitch = Math.max(glitch, amount * k); glitchTicks = Math.max(glitchTicks, ticks); playUi(ModSounds.GLITCH.get(), 0.7f, 1f); }
            case FxPayload.FLASH -> flash = amount;
            case FxPayload.JUMPSCARE -> { jumpscare = ticks; shake = 1.2f * k; shakeTicks = ticks; staticLevel = 0.9f * k; staticTicks = ticks; }
            case FxPayload.BLACKOUT -> { blackout = ticks; blackoutTotal = ticks; }
            case FxPayload.NOTE -> dev.verity.client.CityConsent.leaveNote();
            case FxPayload.WALL -> dev.verity.client.FourthWall.run(ticks);
            case FxPayload.SEE -> { if (ticks >= 0 && ticks < FxPayload.SEE_KINDS.length) Sanity.force(FxPayload.SEE_KINDS[ticks]); }
            default -> {}
        }
    }

    private static void playUi(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stage = 0; daysLeft = -1; targetDarkness = targetDread = darkness = dread = 0; music = false; night = 0;
            emotion = 0; corruption = 0; carrierId = -1;
            stopLoops(mc);
            return;
        }
        if (mc.isPaused()) return;
        tick++;
        if (shakeTicks > 0 && --shakeTicks == 0) shake = 0;
        if (staticTicks > 0 && --staticTicks == 0) staticLevel = 0;
        if (glitchTicks > 0 && --glitchTicks == 0) glitch = 0;
        if (jumpscare > 0) jumpscare--;
        if (blackout > 0) blackout--;
        flash *= 0.8f;
        darkness += (targetDarkness - darkness) * 0.04f;
        dread += (targetDread - dread) * 0.05f;
        updateNight(mc);

        // how close is the thing?
        float near = 0f;
        RandomSource rnd = mc.level.random;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof VerityMonster m) {
                float d = m.distanceTo(mc.player);
                // while he only stands and watches you get the heartbeat, not the full interference of a chase
                boolean watching = m.getState() == VerityMonster.STATE_WATCH || m.getState() == VerityMonster.STATE_PEER;
                if (m.getState() != VerityMonster.STATE_CALM) near = Math.max(near, Mth.clamp((34f - d) / 34f, 0f, 1f) * (watching ? 0.6f : 1f));
                // it sheds darkness
                if (rnd.nextInt(2) == 0) {
                    mc.level.addParticle(rnd.nextInt(8) == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, m.getX() + (rnd.nextDouble() - 0.5) * 1.6,
                            m.getY() + rnd.nextDouble() * 5.0, m.getZ() + (rnd.nextDouble() - 0.5) * 1.6, 0, 0.01, 0);
                }
                if (rnd.nextInt(3) == 0) mc.level.addParticle(ParticleTypes.ASH, m.getX() + (rnd.nextDouble() - 0.5) * 3, m.getY() + rnd.nextDouble() * 6.5,
                        m.getZ() + (rnd.nextDouble() - 0.5) * 3, 0, 0, 0);
            }
        }
        proximity += (near - proximity) * 0.1f;

        // heart: faster the closer it is, the more afraid you ought to be
        float fear = Math.max(Math.max(proximity, dread * 0.6f), Sanity.dread());
        if (fear > 0.25f && --heartTimer <= 0) {
            heartTimer = (int) Mth.lerp(fear, 26f, 9f);
            playUi(ModSounds.HEARTBEAT.get(), 0.25f + fear * 0.75f, 1f);
        }
        if (proximity > 0.55f && rnd.nextInt(30) == 0) trigger(FxPayload.STATIC, proximity * 0.5f, 8);

        // loops
        if (music && (tune == null || !mc.getSoundManager().isActive(tune))) {
            tune = new SimpleSoundInstance(ModSounds.OLD_TUNE.get().getLocation(), SoundSource.RECORDS, 0.45f, 1f, SoundInstance.createUnseededRandom(),
                    true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
            mc.getSoundManager().play(tune);
        } else if (!music && tune != null) {
            mc.getSoundManager().stop(tune);
            tune = null;
        }
        boolean wantDrone = darkness > 0.25f || dread > 0.45f;
        if (wantDrone && (drone == null || !mc.getSoundManager().isActive(drone))) {
            drone = new SimpleSoundInstance(ModSounds.DRONE.get().getLocation(), SoundSource.AMBIENT, 0.35f, 1f, SoundInstance.createUnseededRandom(),
                    true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
            mc.getSoundManager().play(drone);
        } else if (!wantDrone && drone != null) {
            mc.getSoundManager().stop(drone);
            drone = null;
        }
    }

    /** Night here is not the blue twilight of vanilla: past a dozen blocks there is nothing at all. */
    private static void updateNight(Minecraft mc) {
        float target = 0f;
        if (mc.level.dimensionType().hasSkyLight() && !mc.level.dimensionType().hasFixedTime()) {
            long t = mc.level.getDayTime() % 24000L;
            target = t < 12200 ? 0f : t < 13600 ? (t - 12200) / 1400f : t < 22400 ? 1f : t < 23700 ? 1f - (t - 22400) / 1300f : 0f;
        }
        if (mc.player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) target *= 0.2f;
        target *= VerityConfig.NIGHT_DARKNESS.get().floatValue();
        night += (target - night) * 0.03f;

        net.minecraft.core.BlockPos eye = net.minecraft.core.BlockPos.containing(mc.player.getEyePosition());
        int light = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye);
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.level.block.Block held = net.minecraft.world.level.block.Block.byItem(mc.player.getItemInHand(hand).getItem());
            if (held.defaultBlockState().getLightEmission() >= 10) light = Math.max(light, 11);         // a torch in the hand is a poor lamp, but a lamp
        }
        lightRelief += (Mth.clamp(light / 14f, 0f, 1f) - lightRelief) * 0.1f;
    }

    private static void stopLoops(Minecraft mc) {
        if (tune != null) { mc.getSoundManager().stop(tune); tune = null; }
        if (drone != null) { mc.getSoundManager().stop(drone); drone = null; }
    }

    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        float s = Math.max(shake, proximity * 0.25f);
        if (s <= 0.001f) return;
        double t = System.nanoTime() / 1e9;
        event.setYaw(event.getYaw() + (float) (Math.sin(t * 61.0) + Math.sin(t * 37.3)) * s * 1.4f);
        event.setPitch(event.getPitch() + (float) (Math.sin(t * 53.0 + 1.0) + Math.sin(t * 29.7)) * s * 1.2f);
        event.setRoll(event.getRoll() + (float) Math.sin(t * 43.0 + 2.0) * s * 2.2f);
    }

    /** The dark closes in: fog pulled close and turned black, by the story (the hunt, the lab) and by every night. */
    public static void onFog(ViewportEvent.RenderFog event) {
        float d = Math.max(Math.max(darkness, blackoutAmount()), Sanity.fog());
        if (d <= 0.02f && night <= 0.02f) return;
        float far = event.getFarPlaneDistance(), near = event.getNearPlaneDistance();
        if (d > 0.02f) {
            near = Mth.lerp(d, near, 0.5f);
            far = Mth.lerp(d, far, 9f);
        }
        if (night > 0.02f) {
            float reach = Mth.lerp(lightRelief, 10f, 19f);          // how far you can see at midnight, in blocks
            far = Math.min(far, Mth.lerp(night, event.getFarPlaneDistance(), reach));
            near = Math.min(near, Mth.lerp(night, event.getNearPlaneDistance(), 0f));
        }
        event.setNearPlaneDistance(near);
        event.setFarPlaneDistance(far);
        event.setCanceled(true);
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float d = Math.max(Math.max(darkness, blackoutAmount()), night);
        if (d <= 0.02f) return;
        float k = 1f - d * 0.97f;
        event.setRed(event.getRed() * k); event.setGreen(event.getGreen() * k); event.setBlue(event.getBlue() * k);
    }

    /** How strongly the whole view is dimmed at night (0..1); less where there is light. */
    public static float nightDim() {
        return night * (1f - 0.7f * lightRelief) * 0.52f;
    }

    /** 0..1: fades to black fast, holds, fades back slowly. */
    public static float blackoutAmount() {
        if (blackout <= 0 || blackoutTotal <= 0) return 0f;
        float done = 1f - blackout / (float) blackoutTotal;
        return done < 0.12f ? done / 0.12f : done > 0.7f ? (1f - done) / 0.3f : 1f;
    }

    private ScreenFx() {}
}
