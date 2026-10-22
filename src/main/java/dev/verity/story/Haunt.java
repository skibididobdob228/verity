package dev.verity.story;

import dev.verity.entity.Emotion;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The things that happen around the player between the plot's big moments, more often and worse the further the story
 * has gone: steps behind you, a knock on the door, animals that all turn to look, a tall figure at the edge of the dark
 * that is gone when you look straight at it, and the night you wake up and it is standing over the bed.
 *
 * Nothing here is saved: a haunting that was in progress when the world closed is simply over.
 */
final class Haunt {
    private static final String TAG = "verity_watcher";
    /** FAR stands and watches; BEHIND is gone when you turn; FIXED plays out its time; PEEK is gone when you stare; CRAWL runs across; RUSH comes at you. */
    private static final int FAR = 0, BEHIND = 1, FIXED = 2, PEEK = 3, CRAWL = 4, RUSH = 5;

    private final Story story;
    /** The player the current haunting is happening to. */
    @Nullable private UUID focus;
    private int cooldown = 100, sweep;
    private String last = "";
    @Nullable private UUID watcherId;
    private long watcherUntil, darkUntil, noSleepUntil, stareUntil;
    private int watcherMode, gaze;
    private double flee;
    private Vec3 crawlStep = Vec3.ZERO;
    private boolean noticed;
    private float dark;
    private final List<Mob> starers = new ArrayList<>();

    Haunt(Story story) { this.story = story; }

    /** How bad things are right now, 0 (nothing happens) to 5. */
    int level() {
        return switch (story.stage()) {
            case QUESTIONS -> story.daysInStage() >= 1 ? 1 : 0;
            case WATCHED -> 2;
            case COUNTDOWN -> Mth.clamp(5 - story.daysLeft(), 2, 5);
            case POSSESSIVE -> Math.min(5, 2 + story.anger() / 2);
            default -> 0;
        };
    }

    /** Something is standing out there looking at the player right now. */
    boolean watching() { return watcher() != null; }

    /** Extra darkness a haunting is causing right now (0..1). */
    float dark() { return story.now() < darkUntil ? dark : 0f; }

    @Nullable
    private VerityMonster watcher() {
        if (watcherId == null) return null;
        for (ServerLevel l : story.server().getAllLevels()) {
            if (l.getEntity(watcherId) instanceof VerityMonster m) return m;
        }
        watcherId = null;
        return null;
    }

    private RandomSource rnd() { return story.overworld().random; }

    /** A sound only this player and whoever is close to them hear: nobody across the map shares it. */
    private void hear(ServerPlayer p, Vec3 at, Holder<SoundEvent> sound, float volume, float pitch) {
        long seed = rnd().nextLong();
        for (ServerPlayer q : story.server().getPlayerList().getPlayers()) {      // whoever is in the house with them hears the knock too
            if (q == p || q.level() == p.level() && q.distanceToSqr(at) < 20 * 20) {
                q.connection.send(new ClientboundSoundPacket(sound, SoundSource.HOSTILE, at.x, at.y, at.z, volume, pitch, seed));
            }
        }
    }

    private static Vec3 behind(ServerPlayer p, double distance) {
        return p.position().add(Vec3.directionFromRotation(0, p.getYRot() + 180f).scale(distance)).add(0, 0.5, 0);
    }

    // ------------------------------------------------------------------ once a second
    void think(ServerPlayer p) {
        if (++sweep % 10 == 0) sweepStale(p.serverLevel());
        int level = level();
        if (level == 0 || p.isSpectator() || p.level() != story.overworld()) return;
        boolean night = story.isNight();
        if (cooldown > 0) { cooldown -= night ? 2 : 1; return; }
        if (story.speaking() || watcher() != null || p.isSleeping() || story.monster() != null || story.stalker().active()) return;
        long t = story.overworld().getDayTime() % 24000L;
        boolean dusk = t > 11800 && t < 13000;
        List<String> pool = new ArrayList<>(List.of("steps", "whisper"));
        if (night) pool.add("knock");
        if (level >= 2) {
            pool.add("stare");
            pool.add("join");
            if (night || dusk) { pool.add("watcher"); pool.add("watcher"); }
        }
        if (level >= 3) { pool.add("dark"); pool.add("flash"); pool.add("echo"); }
        if (level >= 4 && night) pool.add("behind");
        // the tall one himself, more and more often
        if (level >= 2) { pool.add("crawl"); pool.add("window"); }
        if (level >= 2 && night) pool.add("doorway");
        if (level >= 3 && night) { pool.add("rush"); pool.add("rise"); }
        // him, for real: not something only you see, but the thing itself, walking about the world
        BlockPos home = story.home();
        boolean indoors = home != null && p.blockPosition().distSqr(home) < 14 * 14 && !p.level().canSeeSky(p.blockPosition().above());
        if (level >= 2 && night) { pool.add("stalk"); pool.add("stalk"); pool.add("stalk"); pool.add("lights"); }
        if (level >= 2 && !night && rnd().nextInt(3) == 0) pool.add("stalk");
        if (level >= 3 && night) { pool.add("chase"); pool.add("chase"); if (indoors) { pool.add("prowl"); pool.add("prowl"); pool.add("prowl"); } }
        if (level >= 2 && night && indoors) pool.add("prowl");
        pool.removeIf(last::equals);
        String pick = pool.get(rnd().nextInt(pool.size()));
        focus = p.getUUID();
        if (run(pick, p)) {
            story.addSanity(4f);
            last = pick;
            cooldown = Math.max(35, 200 - level * 35) + rnd().nextInt(50);       // more often the further it has gone
            if (pick.equals("stalk") || pick.equals("chase") || pick.equals("prowl")) cooldown += 90;     // after the real thing, a breather
        } else {
            cooldown = 8;
        }
    }

    /** Starts one haunting by name (also used by /verity haunt). @return false if it could not happen here */
    boolean run(String what, ServerPlayer p) {
        focus = p.getUUID();
        int level = Math.max(level(), 2);
        return switch (what) {
            case "steps" -> steps(p, level);
            case "whisper" -> story.say(null, Lines.pick("haunt_whisper")) > 0;
            case "knock" -> knock(p, level);
            case "stare" -> stare(p);
            case "join" -> join(p, level);
            case "watcher" -> spawnWatcher(p, FAR, 440) != null;
            case "behind" -> behindYou(p);
            case "dark" -> lightsDie(p);
            case "flash" -> { story.fx(p, FxPayload.JUMPSCARE, 1f, 3); yield true; }
            case "echo" -> echo(p);
            case "visit" -> { visit(p); yield true; }
            case "nightmare" -> { nightmare(p, true); yield true; }
            case "crawl" -> crawl(p);
            case "window" -> window(p);
            case "doorway" -> doorway(p);
            case "rush" -> rush(p);
            case "rise" -> rise(p);
            case "stalk" -> story.stalker().start(Stalker.Kind.STALK, p);
            case "chase" -> story.stalker().start(Stalker.Kind.CHASE, p);
            case "prowl" -> story.stalker().start(Stalker.Kind.PROWL, p);
            case "lights" -> story.isNight() && story.stalker().snuffAround(p, 14, 4) > 0;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ every tick
    void tick() {
        ServerPlayer p = focus == null ? null : story.server().getPlayerList().getPlayer(focus);
        if (p == null) p = story.ownerPlayer();
        if (!starers.isEmpty()) {
            if (p == null || story.now() > stareUntil) {
                starers.clear();
            } else {
                for (Mob m : starers) {
                    if (!m.isAlive()) continue;
                    m.getNavigation().stop();
                    m.getLookControl().setLookAt(p, 90f, 90f);
                    float yaw = (float) (Mth.atan2(p.getZ() - m.getZ(), p.getX() - m.getX()) * Mth.RAD_TO_DEG) - 90f;
                    m.setYRot(Mth.rotLerp(0.35f, m.getYRot(), yaw));
                    m.yBodyRot = m.getYRot();
                    m.yHeadRot = Mth.rotLerp(0.5f, m.yHeadRot, yaw);
                }
            }
        }
        VerityMonster w = watcher();
        if (w == null) return;
        if (p == null || p.level() != w.level() || story.now() > watcherUntil || story.monster() != null) { vanish(p, false); return; }
        if (watcherMode == FIXED) return;
        if (watcherMode == CRAWL) {                                       // across your view on all fours, fast, and away
            Vec3 next = w.position().add(crawlStep);
            Vec3 floor = standable((ServerLevel) w.level(), BlockPos.containing(next.add(0, 0.5, 0)), 3);
            w.moveTo(next.x, floor != null ? floor.y : next.y, next.z, (float) (Mth.atan2(crawlStep.z, crawlStep.x) * Mth.RAD_TO_DEG) - 90f, 0);
            if (story.now() % 4 == 0) hear(p, w.position(), ModSounds.STEP, 0.9f, 1.3f);
            return;
        }
        if (watcherMode == RUSH) {                                        // it comes straight at you, and there is nothing when it arrives
            Vec3 step = p.position().subtract(w.position()).multiply(1, 0, 1).normalize().scale(0.6);
            Vec3 next = w.position().add(step);
            Vec3 floor = standable((ServerLevel) w.level(), BlockPos.containing(next.add(0, 0.5, 0)), 3);
            w.moveTo(next.x, floor != null ? floor.y : next.y, next.z, (float) (Mth.atan2(step.z, step.x) * Mth.RAD_TO_DEG) - 90f, 0);
            if (story.now() % 5 == 0) hear(p, w.position(), ModSounds.STEP, 1.4f, 0.9f);
            if (w.distanceTo(p) < 2.6) {
                hear(p, p.position(), ModSounds.JUMPSCARE, 1f, 1f);
                story.fx(p, FxPayload.JUMPSCARE, 1f, 6);
                story.note("из темноты на игрока бросилась высокая фигура и исчезла прямо перед ним");
                vanish(p, false);
            }
            return;
        }
        Vec3 to = w.position().add(0, 3.4, 0).subtract(p.getEyePosition()).normalize();
        double dot = p.getLookAngle().dot(to);
        if (watcherMode == BEHIND) {
            // you get a fraction of a second of it, and then it was never there
            if (dot > 0.3 && ++gaze > 5) vanish(p, true);
            return;
        }
        if (dot > 0.94) gaze++;
        if (!noticed && gaze > 4) {
            noticed = true;
            story.fx(p, FxPayload.STATIC, 0.35f, 14);
        }
        if (gaze > 32 || w.distanceTo(p) < flee) vanish(p, true);
    }

    private void vanish(@Nullable ServerPlayer p, boolean seen) {
        VerityMonster w = watcher();
        int mode = watcherMode;
        if (w != null) w.discard();
        watcherId = null;
        if (p == null || !seen) return;
        story.fx(p, FxPayload.GLITCH, 0.6f, 10);
        story.fx(p, FxPayload.STATIC, 0.7f, 16);
        story.note(mode == BEHIND ? "игрок обернулся и на миг увидел у себя за спиной высокую фигуру; она тут же исчезла"
                : "игрок заметил в темноте высокую тощую фигуру, которая стояла и смотрела; когда он присмотрелся, она исчезла");
        if (story.speaking()) return;
        if (mode == BEHIND) {
            if (rnd().nextBoolean()) story.later(18, () -> story.say(null, "h_turned"));
        } else if (story.setFlag("seen:1")) {
            story.later(25, () -> story.say(null, "w_seen_1"));
        } else if (story.setFlag("seen:2")) {
            story.later(25, () -> story.say(null, "w_seen_2"));
        }
    }

    // ------------------------------------------------------------------ the tall one himself
    /** Something on all fours crosses the edge of your sight, very fast, and is gone. */
    private boolean crawl(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        int side = rnd().nextBoolean() ? 1 : -1;
        Vec3 fwd = Vec3.directionFromRotation(0, p.getYRot()), right = new Vec3(-fwd.z, 0, fwd.x);
        double ahead = story.isNight() ? 7 + rnd().nextDouble() * 2 : 11 + rnd().nextDouble() * 4;
        Vec3 from = p.position().add(fwd.scale(ahead)).add(right.scale(side * 12));
        Vec3 at = standable(level, BlockPos.containing(from.add(0, 0.5, 0)), 4);
        if (at == null) return false;
        putWatcher(p, at, CRAWL, 60);
        crawlStep = right.scale(-side * 0.45);
        story.note("через поле зрения игрока на четвереньках быстро пробежало что-то длинное и тощее");
        return true;
    }

    /** It looks in at you through the glass. */
    private boolean window(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        BlockPos c = p.blockPosition(), best = null;
        Vec3 look = p.getLookAngle();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-9, -1, -9), c.offset(9, 3, 9))) {
            BlockState s = level.getBlockState(pos);
            if (!s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS) && !s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES)) continue;
            Vec3 to = Vec3.atCenterOf(pos).subtract(p.getEyePosition());
            if (to.length() < 2 || look.dot(to.normalize()) < 0.2) continue;
            if (best == null || pos.distSqr(c) < best.distSqr(c)) best = pos.immutable();
        }
        if (best == null) return false;
        Vec3 out = Vec3.atCenterOf(best).subtract(p.position()).multiply(1, 0, 1).normalize();
        Vec3 at = null;
        for (double d = 1.2; d <= 3 && at == null; d += 0.6) at = standable(level, BlockPos.containing(Vec3.atCenterOf(best).add(out.scale(d)).add(0, -1, 0)), 3);
        if (at == null) return false;
        putWatcher(p, at, PEEK, 160);
        Vec3 glass = Vec3.atCenterOf(best);
        story.later(10, () -> hear(p, glass, ModSounds.KNOCK, 0.8f, 1.6f));
        story.later(22, () -> hear(p, glass, ModSounds.KNOCK, 0.8f, 1.6f));
        story.note("кто-то высокий смотрел на игрока через окно и стучал по стеклу");
        return true;
    }

    /** It is standing in the doorway. */
    private boolean doorway(ServerPlayer p) {
        BlockPos door = findDoor(p, 10);
        if (door == null) return false;
        openDoor(p.serverLevel(), door);
        Vec3 out = Vec3.atCenterOf(door).subtract(p.position()).multiply(1, 0, 1).normalize();     // just the other side of it
        Vec3 at = null;
        for (double d = 1.0; d <= 2.6 && at == null; d += 0.4) at = standable(p.serverLevel(), BlockPos.containing(Vec3.atCenterOf(door).add(out.scale(d))), 2);
        if (at == null) return false;
        putWatcher(p, at, PEEK, 140);
        hear(p, at, ModSounds.GROWL, 0.6f, 0.6f);
        story.note("в дверном проёме дома игрока стояла высокая фигура");
        return true;
    }

    /** Out of the dark, straight at you. */
    private boolean rush(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        for (int tries = 0; tries < 8; tries++) {
            Vec3 dir = Vec3.directionFromRotation(0, p.getYRot() + rnd().nextInt(61) - 30);
            BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(p.position().add(dir.scale(story.isNight() ? 13 : 22))));
            if (Math.abs(spot.getY() - p.getY()) > 5) continue;
            VerityMonster m = putWatcher(p, Vec3.atBottomCenterOf(spot), RUSH, 140);
            m.setState(VerityMonster.STATE_HUNT);
            hear(p, m.position(), ModSounds.SCREAM, 1.2f, 1.1f);
            return true;
        }
        return false;
    }

    /** It climbs up out of the ground in front of you, screams, and is not there. */
    private boolean rise(ServerPlayer p) {
        Vec3 dir = Vec3.directionFromRotation(0, p.getYRot());
        Vec3 at = standable(p.serverLevel(), BlockPos.containing(p.position().add(dir.scale(story.isNight() ? 5 : 8)).add(0, 0.5, 0)), 3);
        if (at == null) return false;
        VerityMonster m = putWatcher(p, at, FIXED, 75);
        m.setState(VerityMonster.STATE_RISE);
        story.later(45, () -> {
            VerityMonster w = watcher();
            if (w == null) return;
            w.setState(VerityMonster.STATE_SCREAM);
            hear(p, w.position(), ModSounds.SCREAM, 1.5f, 0.9f);
            story.fx(p, FxPayload.SHAKE, 0.8f, 25);
        });
        story.note("прямо перед игроком из земли поднялось существо, закричало и исчезло");
        return true;
    }

    /** Watchers left behind by a crash or a closed world. */
    private void sweepStale(ServerLevel level) {
        for (VerityMonster m : level.getEntities(EntityTypeTest.forClass(VerityMonster.class), e -> e.getTags().contains(TAG))) {
            if (!m.getUUID().equals(watcherId)) m.discard();
        }
    }

    // ------------------------------------------------------------------ the figure
    @Nullable
    private VerityMonster spawnWatcher(ServerPlayer p, int mode, int lifeTicks) {
        ServerLevel level = p.serverLevel();
        Vec3 at = null;
        if (mode == BEHIND) {
            at = standable(level, BlockPos.containing(behind(p, 4.5)), 3);
        } else {
            for (int tries = 0; tries < 10 && at == null; tries++) {
                // near enough to be made out through the dark, far enough to doubt it
                // (at night the body is lost in the fog past ten blocks or so, and only the two lights of its eyes show)
                double dist = story.isNight() ? nightReach(p) * (0.85 + rnd().nextDouble() * 0.4) : 26 + rnd().nextDouble() * 9;
                flee = Math.max(4.5, dist - 4);
                Vec3 dir = Vec3.directionFromRotation(0, p.getYRot() + rnd().nextInt(81) - 40);
                BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(p.getX() + dir.x * dist, p.getY(), p.getZ() + dir.z * dist));
                if (Math.abs(spot.getY() - p.getY()) < 7 && level.getFluidState(spot.below()).isEmpty()) at = Vec3.atBottomCenterOf(spot);
            }
        }
        return at == null ? null : putWatcher(p, at, mode, lifeTicks);
    }

    /** How far this player can see at night, in blocks: the mod's darkness ends sight at about ten, light pushes it out to nineteen. */
    private static double nightReach(ServerPlayer p) {
        int light = p.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, BlockPos.containing(p.getEyePosition()));
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            if (net.minecraft.world.level.block.Block.byItem(p.getItemInHand(hand).getItem()).defaultBlockState().getLightEmission() >= 10) light = Math.max(light, 11);
        }
        return Mth.lerp(Mth.clamp(light / 14f, 0f, 1f), 10.0, 19.0);
    }

    private VerityMonster putWatcher(ServerPlayer p, Vec3 at, int mode, int lifeTicks) {
        ServerLevel level = p.serverLevel();
        VerityMonster old = watcher();
        if (old != null) old.discard();
        VerityMonster m = ModEntities.MONSTER.get().create(level);
        float yaw = (float) (Mth.atan2(p.getZ() - at.z, p.getX() - at.x) * Mth.RAD_TO_DEG) - 90f;
        m.moveTo(at.x, at.y, at.z, yaw, 0);
        m.yBodyRot = yaw;
        m.yHeadRot = yaw;
        m.setState(mode == FAR ? VerityMonster.STATE_WATCH : VerityMonster.STATE_PEER);
        m.setNoAi(mode == CRAWL || mode == RUSH);
        m.setPrey(p.getUUID());
        m.setSilent(true);
        m.addTag(TAG);
        level.addFreshEntity(m);
        watcherId = m.getUUID();
        watcherUntil = story.now() + lifeTicks;
        watcherMode = mode;
        gaze = 0;
        noticed = false;
        return m;
    }

    /** A place to stand near a position: air to stand in, something solid under it. */
    @Nullable
    private static Vec3 standable(ServerLevel level, BlockPos around, int reach) {
        for (int dy = 0; dy <= reach; dy++) {
            for (int sign : new int[] {-1, 1}) {
                BlockPos pos = around.offset(0, dy * sign, 0);
                if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                        && !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty() && level.getFluidState(pos).isEmpty()) {
                    return Vec3.atBottomCenterOf(pos);
                }
            }
        }
        return null;
    }

    private boolean behindYou(ServerPlayer p) {
        VerityMonster w = spawnWatcher(p, BEHIND, 180);
        if (w == null) return false;
        hear(p, w.position().add(0, 2, 0), ModSounds.GROWL, 0.55f, 0.6f);
        return true;
    }

    // ------------------------------------------------------------------ the smaller things
    private boolean steps(ServerPlayer p, int level) {
        int n = 6;
        for (int i = 0; i < n; i++) {
            int k = i;
            story.later(1 + i * 9, () -> hear(p, behind(p, 13 - k * 2.1), ModSounds.STEP, 1.5f, 0.8f + rnd().nextFloat() * 0.2f));
        }
        if (level >= 3) {
            story.later(n * 9 + 16, () -> {
                hear(p, behind(p, 2.2), ModSounds.GROWL, 0.5f, 0.7f);
                story.fx(p, FxPayload.SHAKE, 0.3f, 10);
            });
        }
        if (level >= 2 && rnd().nextInt(3) == 0) story.later(n * 9 + 70, () -> { if (!story.speaking()) story.say(null, "h_steps"); });
        return true;
    }

    @Nullable
    private static BlockPos findDoor(ServerPlayer p, int radius) {
        ServerLevel level = p.serverLevel();
        BlockPos c = p.blockPosition(), best = null;
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-radius, -3, -radius), c.offset(radius, 3, radius))) {
            BlockState s = level.getBlockState(pos);
            if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && (best == null || pos.distSqr(c) < best.distSqr(c))) {
                best = pos.immutable();
            }
        }
        return best;
    }

    private void knocks(ServerPlayer p, Vec3 at, int start, float volume) {
        for (int i = 0; i < 3; i++) story.later(start + i * 9, () -> hear(p, at, ModSounds.KNOCK, volume, 0.85f + rnd().nextFloat() * 0.1f));
    }

    private static void openDoor(ServerLevel level, @Nullable BlockPos door) {
        if (door == null) return;
        BlockState s = level.getBlockState(door);
        if (s.getBlock() instanceof DoorBlock d && !d.isOpen(s)) d.setOpen(null, level, s, door, true);
    }

    private boolean knock(ServerPlayer p, int level) {
        BlockPos door = findDoor(p, 10);
        Vec3 at = door != null ? Vec3.atCenterOf(door) : behind(p, 7);
        knocks(p, at, 1, 1.6f);
        knocks(p, at, 55, 2.0f);
        if (level >= 2 && door != null) {
            story.later(30, () -> { if (!story.speaking()) story.say(null, "h_door"); });
            story.later(120, () -> {
                openDoor(p.serverLevel(), door);
                if (!story.speaking()) story.say(null, "h_door_open");
            });
        }
        return true;
    }

    private boolean stare(ServerPlayer p) {
        List<Mob> mobs = p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(20, 8, 20), m -> m instanceof Animal || m instanceof AbstractVillager);
        if (mobs.size() < 2) return false;
        starers.clear();
        starers.addAll(mobs);
        stareUntil = story.now() + 140;
        story.note("все животные вокруг игрока разом замерли и повернулись к нему");
        if (story.setFlag("h_animals")) story.later(50, () -> { if (!story.speaking()) story.say(null, "h_animals"); });
        return true;
    }

    private boolean join(ServerPlayer p, int level) {
        String name = level >= 4 ? p.getGameProfile().getName() : "Verity";
        for (ServerPlayer q : story.server().getPlayerList().getPlayers()) q.sendSystemMessage(Component.translatable("multiplayer.player.joined", name).withStyle(ChatFormatting.YELLOW));
        story.later(160 + rnd().nextInt(200), () -> {
            for (ServerPlayer q : story.server().getPlayerList().getPlayers()) q.sendSystemMessage(Component.translatable("multiplayer.player.left", name).withStyle(ChatFormatting.YELLOW));
        });
        story.note("в чате появилось сообщение, будто в мир вошёл ещё кто-то по имени " + name + "; никто не вошёл");
        return true;
    }

    private boolean lightsDie(ServerPlayer p) {
        dark = 0.9f;
        darkUntil = story.now() + 110;
        story.sync();
        story.later(45, () -> hear(p, behind(p, 3), ModSounds.STEP, 1.2f, 0.7f));
        if (rnd().nextInt(3) == 0) story.later(125, () -> { if (!story.speaking()) story.say(null, "h_dark"); });
        return true;
    }

    /** He says the player's own last words back to them. */
    private boolean echo(ServerPlayer p) {
        for (int i = story.memory.size() - 1; i >= 0; i--) {
            String[] m = story.memory.get(i);
            if (m[0].equals("user") && !m[1].startsWith("[") && m[1].length() >= 3 && m[1].length() <= 60) {
                story.sayText(null, m[1], Emotion.CREEPY, "flat");
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the two set pieces
    /** The second night of being watched: it comes to the door. */
    void visit(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        BlockPos door = findDoor(p, 12);
        Vec3 at = door != null ? Vec3.atCenterOf(door) : behind(p, 7);
        story.note("ночью в дверь игрока стучали; дверь открылась сама, за ней стояла высокая фигура, потом она исчезла");
        story.addSanity(10f);
        knocks(p, at, 1, 1.8f);
        story.later(40, () -> story.say(null, "h_door"));
        knocks(p, at, 70, 2.4f);
        story.later(125, () -> {
            openDoor(level, door);
            dark = 0.4f;
            darkUntil = story.now() + 150;
            story.sync();
        });
        story.later(135, () -> {
            // it stands just outside: on the far side of the door from you, or simply in front of you
            Vec3 from = door != null ? Vec3.atCenterOf(door) : p.position();
            Vec3 dir = door != null ? from.subtract(p.position()).multiply(1, 0, 1) : Vec3.directionFromRotation(0, p.getYRot());
            dir = dir.lengthSqr() < 0.01 ? Vec3.directionFromRotation(0, p.getYRot()) : dir.normalize();
            Vec3 spot = null;
            for (double d = door != null ? 3.5 : 6.5; d >= 2 && spot == null; d -= 0.75) spot = standable(level, BlockPos.containing(from.add(dir.scale(d))), 3);
            if (spot == null) return;
            VerityMonster w = putWatcher(p, spot, FIXED, 85);
            hear(p, w.position().add(0, 2, 0), ModSounds.GROWL, 0.9f, 0.6f);
        });
        story.later(220, () -> {
            vanish(p, false);
            story.fx(p, FxPayload.STATIC, 0.8f, 20);
            story.fx(p, FxPayload.GLITCH, 0.7f, 12);
        });
        story.later(275, () -> story.say(null, "w_visit"));
    }

    /** @return why the player cannot sleep right now, or null */
    @Nullable
    Component onTrySleep(ServerPlayer p) {
        if (story.now() < noSleepUntil) return Component.literal("Вы не можете уснуть: сердце колотится.").withStyle(ChatFormatting.DARK_RED);
        boolean due = switch (story.stage()) {
            case WATCHED -> story.setFlag("nm:w");
            case COUNTDOWN -> story.daysLeft() > 0 && story.daysLeft() <= 2 && story.setFlag("nm:c");
            case POSSESSIVE -> story.setFlag("nm:p" + story.storyDay() / 2);
            default -> false;
        };
        if (due) story.later(45, () -> nightmare(p, false));
        return null;
    }

    /** You wake in the middle of the night and it is standing over the bed. Then it was a dream. */
    private void nightmare(ServerPlayer p, boolean forced) {
        if (!forced && !p.isSleeping()) return;
        ServerLevel level = p.serverLevel();
        story.note("игрок лёг спать и проснулся среди ночи: над кроватью стояла высокая фигура; ты сказал, что ему приснилось");
        story.fx(p, FxPayload.BLACKOUT, 1f, 46);
        story.addSanity(15f);
        noSleepUntil = story.now() + 20 * 75;
        story.later(8, () -> {
            if (p.isSleeping()) p.stopSleepInBed(true, true);
            Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
            Vec3 best = null;
            double bestScore = -9;
            for (int a = 0; a < 8; a++) {
                Vec3 dir = Vec3.directionFromRotation(0, a * 45f);
                Vec3 spot = standable(level, BlockPos.containing(p.position().add(dir.scale(2.6)).add(0, 0.5, 0)), 1);
                if (spot != null && dir.dot(look) > bestScore) { best = spot; bestScore = dir.dot(look); }
            }
            if (best == null) best = p.position().add(look.scale(2.5));
            VerityMonster w = putWatcher(p, best, FIXED, 60);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, w.position().add(0, 2.6, 0));
            hear(p, w.position().add(0, 2, 0), ModSounds.GROWL, 0.8f, 0.55f);
        });
        story.later(56, () -> {
            VerityMonster w = watcher();
            if (w != null) hear(p, w.position(), ModSounds.JUMPSCARE, 0.9f, 1f);
            vanish(p, false);
            story.fx(p, FxPayload.JUMPSCARE, 1f, 8);
        });
        String after = story.setFlag("nm:first") ? "nm_wake" : Lines.pick("nm_wake");
        story.later(120, () -> story.say(null, after));
        story.later(230, () -> { if (!story.speaking()) story.byName(p, null, "Спи, %s. Я посмотрю.", Emotion.CREEPY, "flat"); });
    }
}
