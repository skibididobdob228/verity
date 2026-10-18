package dev.verity.client.fx;

import dev.verity.VerityConfig;
import dev.verity.entity.Emotion;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * What a failing mind does to what you see and hear. The server keeps the number (0..100; the level is a sixth of it,
 * 0 = sound, 6 = gone); everything here is this player's private experience of it and exists on this client only:
 * sounds nobody made, a shape at the edge of the eye, Verities sitting in the grass that are not there, the tall thing
 * that is gone when you look straight at it, and at the top level the one that runs at you.
 * None of it touches the world, and other players see none of it.
 *
 * Verity himself changes with it too, for you alone: his face slips more often, his voice sinks, his words come apart.
 */
public final class Sanity {
    public static final int MAX_LEVEL = 6;
    private static final float[] CORRUPTION = {0f, 0f, 0.12f, 0.28f, 0.46f, 0.68f, 0.95f};
    private static final float[] VOICE = {1f, 1f, 1f, 0.97f, 0.93f, 0.88f, 0.8f};
    private static final String[] WORDS = {"ОН ВРЁТ", "НЕ ВЕРЬ", "ПРОСНИСЬ", "ЭТО НЕ ОН", "ОН ЗА СПИНОЙ", "НЕ СМОТРИ"};
    private static final int BALL = 0, FIGURE = 1, CHARGER = 2, DOUBLE = 3;

    private record Ghost(Entity entity, long until, int kind) {}
    private record Later(long at, Runnable action) {}

    private static final Random RNG = new Random();
    private static final List<Ghost> ghosts = new ArrayList<>();
    private static final List<Later> queue = new ArrayList<>();
    private static float target;
    /** The server's number, eased so that the world does not change in one frame. */
    public static float points;
    /** Read by the HUD: the thing at the edge of your eye (ticks left, which side), and a word that is not what he said. */
    public static int shadowTicks, shadowSide = 1, wordTicks;
    private static String word = "";
    private static long tick;
    private static int soundTimer = 500, shadowTimer = 500, ballTimer = 400, figureTimer = 500, chargeTimer = 500, chatTimer = 900, hurtTimer = 1100, gaze;
    private static int nextId = -1_200_000;

    public static void set(float serverPoints) { target = serverPoints; }
    public static int level() { return Mth.clamp((int) (points / 15f), 0, MAX_LEVEL); }

    // ------------------------------------------------------------------ what the rest of the client asks
    /** How badly Verity's face comes apart for this player, whatever state he is really in. */
    public static float corruption() { return CORRUPTION[level()]; }
    public static float voicePitch() { return VOICE[level()]; }
    /** Fog pulled in by a mind that no longer trusts distance (0..1, same scale as the story's darkness). */
    public static float fog() { return Math.max(0, level() - 3) * 0.08f; }
    public static float dread() { return Math.max(0, level() - 2) * 0.2f; }

    /** From level four his face is, some of the time, not the one he is wearing. */
    public static Emotion face(Emotion real) {
        int l = level();
        if (l < 4) return real;
        int roll = (int) Math.floorMod((System.currentTimeMillis() / 1300L) * 2654435761L >>> 7, 100L);
        return roll < (l == 4 ? 12 : l == 5 ? 35 : 70) ? (l == 6 ? Emotion.TEETH : Emotion.CREEPY) : real;
    }

    /** His subtitles: letters go missing from level three; from five the whole line is, for a moment, something else. */
    public static String garble(String text) {
        int l = level();
        if (l < 3 || text.isEmpty()) return text;
        if (l >= 5) {
            if (wordTicks > 0) return word;
            if (RNG.nextInt(l == 6 ? 170 : 420) == 0) {
                word = WORDS[RNG.nextInt(WORDS.length)];
                wordTicks = 8 + RNG.nextInt(10);
                return word;
            }
        }
        Random r = new Random(text.hashCode() * 31L + System.currentTimeMillis() / 130L);
        float chance = (l - 2) * 0.022f;
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < sb.length(); i++) {
            if (!Character.isLetter(sb.charAt(i)) || r.nextFloat() >= chance) continue;
            if (r.nextBoolean() && i + 1 < sb.length() && Character.isLetter(sb.charAt(i + 1))) {
                char c = sb.charAt(i);
                sb.setCharAt(i, sb.charAt(i + 1));
                sb.setCharAt(i + 1, c);
            } else {
                sb.setCharAt(i, "#%?@&".charAt(r.nextInt(5)));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ every tick
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            target = points = 0f;
            ghosts.clear();
            queue.clear();
            return;
        }
        if (mc.isPaused()) return;
        tick++;
        points += (target - points) * 0.06f;
        if (shadowTicks > 0) shadowTicks--;
        if (wordTicks > 0) wordTicks--;
        for (Iterator<Later> it = queue.iterator(); it.hasNext(); ) {
            Later l = it.next();
            if (l.at() <= tick) { it.remove(); l.action().run(); }
        }
        tickGhosts(mc);
        int l = level();
        if (l == 0) return;

        if (--soundTimer <= 0) {
            soundTimer = (60 - l * 8 + RNG.nextInt(20)) * 20;
            hearThings(mc, l);
        }
        if (l >= 2 && --shadowTimer <= 0) {
            shadowTimer = (50 - l * 6 + RNG.nextInt(15)) * 20;
            shadowTicks = 9 + RNG.nextInt(8);
            shadowSide = RNG.nextBoolean() ? 1 : -1;
        }
        if (l >= 3 && --ballTimer <= 0) {
            ballTimer = (55 - l * 6 + RNG.nextInt(20)) * 20;
            if (count(BALL) == 0 && count(DOUBLE) == 0) {
                if (RNG.nextInt(3) == 0) seeDouble(mc); else seeBalls(mc, l);
            }
        }
        if (l >= 4 && --figureTimer <= 0) {
            figureTimer = (70 - l * 8 + RNG.nextInt(25)) * 20;
            if (count(FIGURE) == 0 && count(CHARGER) == 0 && noRealMonster(mc)) seeFigure(mc, l);
        }
        if (l >= 4 && --hurtTimer <= 0) {
            hurtTimer = (60 + RNG.nextInt(60)) * 20;
            mc.player.animateHurt(0f);                                      // it did not happen; your body thinks it did
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.8f, 1f, false);
        }
        if (l >= 5 && --chatTimer <= 0) {
            chatTimer = (50 + RNG.nextInt(40)) * 20;
            falseChat(mc);
        }
        if (l >= 6 && --chargeTimer <= 0) {
            chargeTimer = (32 + RNG.nextInt(22)) * 20;
            if (count(CHARGER) == 0 && noRealMonster(mc)) seeCharge(mc);
        }
    }

    /** Starts one hallucination now, whatever the level (development and /verity sanity see ...). */
    public static void force(String what) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        switch (what) {
            case "sound" -> hearThings(mc, Math.max(level(), 4));
            case "shadow" -> { shadowTicks = 16; shadowSide = RNG.nextBoolean() ? 1 : -1; }
            case "balls" -> seeBalls(mc, Math.max(level(), 5));
            case "figure" -> seeFigure(mc, Math.max(level(), 4));
            case "charge" -> seeCharge(mc);
            case "chat" -> falseChat(mc);
            case "double" -> seeDouble(mc);
            default -> {}
        }
    }

    private static int count(int kind) {
        int n = 0;
        for (Ghost g : ghosts) if (g.kind() == kind) n++;
        return n;
    }

    /** While the real one is about there is no need for imagined ones (and they would only give the game away). */
    private static boolean noRealMonster(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof VerityMonster && e.getId() >= 0) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ sounds nobody made
    private static Vec3 behind(Minecraft mc, double distance) {
        float yaw = mc.player.getYRot() + 180f + (RNG.nextFloat() - 0.5f) * 140f;
        return mc.player.position().add(Vec3.directionFromRotation(0, yaw).scale(distance)).add(0, 0.8, 0);
    }

    private static void play(Minecraft mc, Vec3 at, SoundEvent sound, float volume, float pitch) {
        mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.AMBIENT, volume, pitch, false);
    }

    private static void hearThings(Minecraft mc, int l) {
        float pitch = l >= 5 && RNG.nextBoolean() ? 0.55f : 1f;                 // the worst ones come out slowed down
        Vec3 at = behind(mc, 2.5 + RNG.nextDouble() * 4.5);
        switch (RNG.nextInt(l >= 4 ? 9 : l >= 3 ? 7 : l >= 2 ? 4 : 1)) {
            case 1 -> {
                for (int i = 0; i < 5; i++) {
                    double d = 9 - i * 1.6;
                    queue.add(new Later(tick + i * 8L, () -> play(mc, behind(mc, d), SoundEvents.GRASS_STEP, 0.9f, 0.8f * pitch)));
                }
            }
            case 2 -> play(mc, at, SoundEvents.WOODEN_DOOR_OPEN, 0.9f, 0.9f * pitch);
            case 3 -> play(mc, at, SoundEvents.CHEST_OPEN, 0.8f, 0.8f * pitch);
            case 4 -> play(mc, behind(mc, 1.6), SoundEvents.CREEPER_PRIMED, 1f, pitch);
            case 5 -> play(mc, at, SoundEvents.ZOMBIE_AMBIENT, 0.9f, 0.8f * pitch);
            case 6 -> play(mc, at, SoundEvents.ENDERMAN_STARE, 0.7f, pitch);
            case 7 -> play(mc, behind(mc, 3), ModSounds.GROWL.get(), 0.35f, 0.7f * pitch);
            case 8 -> {
                for (int i = 0; i < 3; i++) queue.add(new Later(tick + i * 9L, () -> play(mc, at, ModSounds.KNOCK.get(), 1.2f, 0.9f)));
            }
            default -> play(mc, at, SoundEvents.AMBIENT_CAVE.value(), 0.7f, 0.9f * pitch);
        }
    }

    // ------------------------------------------------------------------ things that are not there
    /** A spot to stand something on near the player's own height: works on open ground, in a cave and indoors. */
    private static Vec3 ground(ClientLevel level, double x, double z, double nearY) {
        for (int dy = 3; dy >= -5; dy--) {
            BlockPos pos = BlockPos.containing(x, nearY + dy, z);
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty() && level.getFluidState(pos).isEmpty()) {
                return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }
        }
        return null;
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90f;
    }

    private static void add(Minecraft mc, Entity e, Vec3 at, int lifeTicks, int kind) {
        float yaw = yawTo(at, mc.player.position());
        e.setId(nextId--);
        e.moveTo(at.x, at.y, at.z, yaw, 0f);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        mc.level.addEntity(e);
        ghosts.add(new Ghost(e, tick + lifeTicks, kind));
    }

    /** Verities in the grass. They watch. They are gone if you walk up to them. */
    private static void seeBalls(Minecraft mc, int l) {
        int n = l <= 3 ? 1 : l == 4 ? 2 + RNG.nextInt(2) : 3 + RNG.nextInt(4);
        for (int i = 0; i < n; i++) {
            float yaw = mc.player.getYRot() + (RNG.nextFloat() - 0.5f) * 200f;
            double dist = 5 + RNG.nextDouble() * 8;
            Vec3 dir = Vec3.directionFromRotation(0, yaw);
            Vec3 at = ground(mc.level, mc.player.getX() + dir.x * dist, mc.player.getZ() + dir.z * dist, mc.player.getY());
            if (at == null) continue;
            VerityBall ball = ModEntities.BALL.get().create(mc.level);
            if (ball == null) return;
            ball.setEmotion(l >= 5 && RNG.nextInt(3) == 0 ? Emotion.TEETH : RNG.nextBoolean() ? Emotion.CREEPY : Emotion.GRIN);
            ball.setCorruption(CORRUPTION[l]);
            add(mc, ball, at, 240 + RNG.nextInt(260), BALL);
        }
    }

    /** Yourself, standing a little way off, looking back at you. Gone as soon as you look properly. */
    private static void seeDouble(Minecraft mc) {
        for (int tries = 0; tries < 8; tries++) {
            float yaw = mc.player.getYRot() + (RNG.nextBoolean() ? 1 : -1) * (25f + RNG.nextFloat() * 35f);    // just off where you are looking
            double dist = 9 + RNG.nextDouble() * 7;
            Vec3 dir = Vec3.directionFromRotation(0, yaw);
            Vec3 at = ground(mc.level, mc.player.getX() + dir.x * dist, mc.player.getZ() + dir.z * dist, mc.player.getY());
            if (at == null) continue;
            net.minecraft.client.resources.PlayerSkin skin = mc.player.getSkin();
            net.minecraft.client.player.RemotePlayer twin = new net.minecraft.client.player.RemotePlayer(mc.level, mc.player.getGameProfile()) {
                @Override
                public net.minecraft.client.resources.PlayerSkin getSkin() { return skin; }        // your own face
            };
            twin.setUUID(java.util.UUID.randomUUID());                                              // the world will not hold two entities with one id
            add(mc, twin, at, 300, DOUBLE);
            gaze = 0;
            return;
        }
    }

    /** The tall one, off to one side, where you are not quite looking. */
    private static void seeFigure(Minecraft mc, int l) {
        boolean dark = ScreenFx.night > 0.5f || mc.level.getBrightness(LightLayer.SKY, mc.player.blockPosition()) < 4;
        for (int tries = 0; tries < 8; tries++) {
            float yaw = mc.player.getYRot() + (RNG.nextBoolean() ? 1 : -1) * (55f + RNG.nextFloat() * 50f);
            double dist = dark ? 7 + RNG.nextDouble() * 3 : 13 + RNG.nextDouble() * 8;
            Vec3 dir = Vec3.directionFromRotation(0, yaw);
            Vec3 at = ground(mc.level, mc.player.getX() + dir.x * dist, mc.player.getZ() + dir.z * dist, mc.player.getY());
            if (at == null) continue;
            VerityMonster m = ModEntities.MONSTER.get().create(mc.level);
            if (m == null) return;
            m.setState(l >= 5 && RNG.nextBoolean() ? VerityMonster.STATE_PEER : VerityMonster.STATE_WATCH);
            add(mc, m, at, 200 + RNG.nextInt(120), FIGURE);
            gaze = 0;
            return;
        }
    }

    /** Level six: it comes out of the distance straight at you, and there is nothing there when it arrives. */
    private static void seeCharge(Minecraft mc) {
        for (int tries = 0; tries < 8; tries++) {
            float yaw = mc.player.getYRot() + (RNG.nextFloat() - 0.5f) * 50f;
            double dist = 15 + RNG.nextDouble() * 5;
            Vec3 dir = Vec3.directionFromRotation(0, yaw);
            Vec3 at = ground(mc.level, mc.player.getX() + dir.x * dist, mc.player.getZ() + dir.z * dist, mc.player.getY());
            if (at == null) continue;
            VerityMonster m = ModEntities.MONSTER.get().create(mc.level);
            if (m == null) return;
            m.setState(VerityMonster.STATE_HUNT);
            add(mc, m, at, 80, CHARGER);
            play(mc, at, ModSounds.SCREAM.get(), 0.6f, 1.1f);
            return;
        }
    }

    private static void tickGhosts(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        for (Iterator<Ghost> it = ghosts.iterator(); it.hasNext(); ) {
            Ghost g = it.next();
            Entity e = g.entity();
            boolean gone = tick > g.until() || e.isRemoved() || e.level() != mc.level;
            double d = e.distanceTo(mc.player);
            float yaw = yawTo(e.position(), mc.player.position());
            if (g.kind() == BALL) {
                e.setYRot(yaw);
                e.setYHeadRot(yaw);
                e.setYBodyRot(yaw);
                e.setXRot((float) -(Mth.atan2(eye.y - e.getY() - 0.25, Math.max(d, 0.1)) * Mth.RAD_TO_DEG));
                gone |= d < 3.2;
            } else if (g.kind() == DOUBLE) {
                e.setYRot(yaw);
                e.setYHeadRot(yaw);
                e.setYBodyRot(yaw);
                e.setXRot((float) -(Mth.atan2(eye.y - e.getEyeY(), Math.max(d, 0.1)) * Mth.RAD_TO_DEG));
                double dot = mc.player.getLookAngle().dot(e.getEyePosition().subtract(eye).normalize());
                if (dot > 0.95) gaze++;
                gone |= gaze > 25 || d < 5;
            } else if (g.kind() == FIGURE) {
                e.setYRot(yaw);
                e.setYHeadRot(yaw);
                e.setYBodyRot(yaw);
                double dot = mc.player.getLookAngle().dot(e.position().add(0, 3.4, 0).subtract(eye).normalize());
                if (dot > 0.9) gaze++;
                gone |= gaze > 7 || d < 5;
            } else {
                Vec3 step = mc.player.position().subtract(e.position()).multiply(1, 0, 1);
                if (d < 2.0) {
                    gone = true;
                    ScreenFx.trigger(FxPayload.JUMPSCARE, 1f, 4);
                    play(mc, mc.player.position(), ModSounds.JUMPSCARE.get(), 0.5f, 1f);
                } else if (!gone) {
                    Vec3 next = e.position().add(step.normalize().scale(0.85));
                    Vec3 floor = ground(mc.level, next.x, next.z, e.getY());
                    e.lerpTo(next.x, floor != null ? floor.y : e.getY(), next.z, yaw, 0f, 2);
                    e.setYHeadRot(yaw);
                    e.setYBodyRot(yaw);
                }
            }
            if (gone) {
                if (!e.isRemoved() && e.level() == mc.level) mc.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
                it.remove();
            }
        }
    }

    private static void falseChat(Minecraft mc) {
        String nick = mc.player.getGameProfile().getName();
        Component line = switch (RNG.nextInt(5)) {
            case 0 -> Component.literal("<Верити> ").withStyle(ChatFormatting.YELLOW).append(Component.literal("ты это тоже видишь?").withStyle(ChatFormatting.WHITE));
            case 1 -> Component.literal("<" + nick + "> я этого не писал");
            case 2 -> Component.literal("<Верити> ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("не уходи не уходи").withStyle(ChatFormatting.WHITE, ChatFormatting.OBFUSCATED));
            case 3 -> Component.translatable("multiplayer.player.joined", nick).withStyle(ChatFormatting.YELLOW);
            default -> Component.literal("<???> он не тот, кем кажется").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        };
        mc.gui.getChat().addMessage(line);
    }

    // ------------------------------------------------------------------ the camera
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        int l = level();
        if (l < 4) return;
        float k = (l - 3) / 3f * Math.min(1f, VerityConfig.FX_INTENSITY.get().floatValue());
        double t = System.nanoTime() / 1e9;
        event.setRoll(event.getRoll() + (float) (Math.sin(t * 0.7) * 1.7 + Math.sin(t * 0.31) * 1.1) * k);       // the floor will not hold still
        event.setYaw(event.getYaw() + (float) Math.sin(t * 0.53) * 0.6f * k);
        event.setPitch(event.getPitch() + (float) Math.sin(t * 0.41) * 0.5f * k);
    }

    public static void onFov(ViewportEvent.ComputeFov event) {
        int l = level();
        if (l < 5) return;
        float k = (l - 4) / 2f * Math.min(1f, VerityConfig.FX_INTENSITY.get().floatValue());
        event.setFOV(event.getFOV() * (1.0 + 0.035 * k * Math.sin(System.nanoTime() / 1e9 * 1.1)));
    }

    private Sanity() {}
}
