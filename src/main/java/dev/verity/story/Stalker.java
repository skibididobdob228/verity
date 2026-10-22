package dev.verity.story;

import dev.verity.VerityConfig;
import dev.verity.entity.VerityMonster;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Him, for real. Not a picture in one player's head that vanishes when looked at: the tall thing itself, an entity in
 * the world that everyone nearby sees, that walks, opens doors, puts out the torches and, if it reaches you, hurts you.
 *
 * STALK: he comes closer whenever nobody is looking, and stands still while somebody is (the diary was right).
 * CHASE: he screams and runs at you, through whatever is in the way, for half a minute.
 * PROWL: you are inside at night; he walks around the house, stops at the windows, tries the doors, the lights go out.
 * Then he walks off into the dark. The torches he put out come back at dawn.
 */
final class Stalker {
    enum Kind { STALK, CHASE, PROWL }

    static final String TAG = "verity_stalker";
    private final Story story;
    @Nullable private UUID monsterId, preyId;
    private Kind kind = Kind.STALK;
    private long startedAt, until, phaseAt;
    private int phase;                        // 0 coming, 1 struck/leaving
    private int waypoint, seenTicks, snuffCooldown;
    private long arrivedAt = -1;
    private boolean daytime;
    private final List<Vec3> route = new ArrayList<>();

    Stalker(Story story) { this.story = story; }

    boolean active() { return monster() != null; }

    @Nullable
    VerityMonster monster() {
        if (monsterId == null) return null;
        if (story.overworld().getEntity(monsterId) instanceof VerityMonster m && m.isAlive()) return m;
        monsterId = null;
        return null;
    }

    // ------------------------------------------------------------------ starting
    boolean start(Kind k, ServerPlayer p) {
        if (active() || story.monster() != null || p.level() != story.overworld() || p.isSpectator()) return false;
        ServerLevel level = story.overworld();
        daytime = !story.isNight();
        Vec3 at;
        if (k == Kind.PROWL) {
            BlockPos home = story.home();
            if (home == null || p.blockPosition().distSqr(home) > 18 * 18) return false;
            route.clear();
            route.addAll(openings(level, home));                  // right outside every window and door of the house
            if (route.size() < 3) {
                for (int i = 0; i < 8; i++) {                     // no windows found: a ring close round the walls
                    double a = Math.PI * 2 * i / 8 + level.random.nextDouble() * 0.3;
                    BlockPos s = ground(level, home.getX() + Math.cos(a) * 7, home.getZ() + Math.sin(a) * 7);
                    if (s != null) route.add(Vec3.atBottomCenterOf(s));
                }
            }
            if (route.size() < 2) return false;
            java.util.Collections.shuffle(route, new java.util.Random(level.random.nextLong()));
            BlockPos s = ground(level, home.getX() + 22, home.getZ() + 4);
            if (s == null) return false;
            at = Vec3.atBottomCenterOf(s);
            waypoint = 0;
        } else {
            double dist = k == Kind.CHASE ? 24 + level.random.nextInt(6) : daytime ? 46 : 34 + level.random.nextInt(8);
            at = null;
            for (int tries = 0; tries < 10 && at == null; tries++) {           // somewhere behind, out of sight
                float yaw = p.getYRot() + 180f + level.random.nextInt(100) - 50;
                Vec3 d = Vec3.directionFromRotation(0, yaw).scale(dist);
                BlockPos s = ground(level, p.getX() + d.x, p.getZ() + d.z);
                if (s != null && Math.abs(s.getY() - p.getY()) < 10) at = Vec3.atBottomCenterOf(s);
            }
            if (at == null) return false;
        }
        VerityMonster m = ModEntities.MONSTER.get().create(level);
        m.moveTo(at.x, at.y, at.z, level.random.nextInt(360), 0);
        m.addTag(TAG);
        m.setPrey(p.getUUID());
        m.setState(k == Kind.CHASE ? VerityMonster.STATE_SCREAM : k == Kind.PROWL ? VerityMonster.STATE_WALK : VerityMonster.STATE_WATCH);
        level.addFreshEntity(m);
        dev.verity.VerityMod.LOGGER.info("Verity stalker: {} at {} (player at {})", k, BlockPos.containing(at), p.blockPosition());
        monsterId = m.getUUID();
        preyId = p.getUUID();
        kind = k;
        if (!daytime) story.setFlag("stalked");
        phase = 0;
        seenTicks = 0;
        startedAt = story.ticks();
        phaseAt = startedAt;
        until = startedAt + switch (k) { case STALK -> daytime ? 1400 : 3000; case CHASE -> 700; case PROWL -> 2200; };
        story.note(switch (k) {
            case STALK -> "ты по-настоящему вышел к игроку и подкрадываешься, когда он не смотрит";
            case CHASE -> "ты по-настоящему погнался за игроком";
            case PROWL -> "ночью ты ходишь вокруг дома игрока, заглядываешь в окна и пробуешь двери";
        });
        if (k == Kind.CHASE) {
            story.sound(m, ModSounds.SCREAM.get(), 5f, 1f);
            for (ServerPlayer q : nearby(m, 48)) story.fx(q, FxPayload.SHAKE, 0.8f, 30);
        } else if (k == Kind.STALK && !daytime) {
            snuffAround(p, 12, 6);                                 // first the lights
        }
        return true;
    }

    // ------------------------------------------------------------------ every tick
    void tick() {
        VerityMonster m = monster();
        if (m == null) return;
        ServerPlayer p = preyId == null ? null : story.server().getPlayerList().getPlayer(preyId);
        long now = story.ticks();
        if (p == null || p.level() != m.level() || p.isSpectator() || m.distanceTo(p) > 140) { vanish(m); return; }
        if (phase == 1) { leave(m, p, now); return; }
        if (now > until || story.monster() != null || !daytime && !story.isNight() && kind != Kind.CHASE) { phase = 1; phaseAt = now; return; }

        double d = m.distanceTo(p);
        boolean seen = observed(m);
        if (now % 100 == 0) dev.verity.VerityMod.LOGGER.debug("Verity stalker: {} d={} state={} seen={} at {} wp={}/{}", kind, (int) d, m.getState(), seen, m.blockPosition().toShortString(), waypoint, route.size());
        if (seen) seenTicks++;
        switch (kind) {
            case STALK -> {
                // he only moves while nobody looks; by day he will not come all the way
                boolean hold = seen || daytime && d < 22;
                m.setPrey(p.getUUID());
                if (hold && m.getState() != VerityMonster.STATE_WATCH) {
                    m.setState(VerityMonster.STATE_WATCH);
                    m.getNavigation().stop();
                    if (seenTicks < 3) for (ServerPlayer q : nearby(m, 64)) if (looksAt(q, m)) story.fx(q, FxPayload.STATIC, 0.25f, 10);
                } else if (!hold && m.getState() != VerityMonster.STATE_HUNT) {
                    m.setState(VerityMonster.STATE_HUNT);
                }
                if (!seen && now % 70 == 0 && d < 30) story.sound(m, ModSounds.GROWL.get(), 0.6f, 0.6f);      // breathing, close
                if (!daytime && d < 18 && --snuffCooldown <= 0) { snuffAround(p, 9, 1); snuffCooldown = 60; }
            }
            case CHASE -> {
                if (now - startedAt == 30) { m.setState(VerityMonster.STATE_HUNT); m.setPrey(p.getUUID()); }
            }
            case PROWL -> prowl(m, p, now, seen);
        }
        if (d < 2.4 && kind != Kind.PROWL || kind == Kind.PROWL && d < 2.2) strike(m, p);
    }

    /** Around the house: from window to window, every door he passes swings open, every torch near him goes out. */
    private void prowl(VerityMonster m, ServerPlayer p, long now, boolean seen) {
        if (route.isEmpty()) { phase = 1; return; }
        BlockPos home = story.home();
        boolean inside = home != null && p.blockPosition().distSqr(home) < 14 * 14 && !p.level().canSeeSky(p.blockPosition().above());
        if (!inside && m.distanceTo(p) < 12) {                   // you came out to him
            kind = Kind.CHASE;
            startedAt = now - 30;
            until = now + 600;
            m.setState(VerityMonster.STATE_HUNT);
            story.sound(m, ModSounds.SCREAM.get(), 5f, 1f);
            return;
        }
        if (seen && arrivedAt < 0) {                             // caught on his way: he stops and looks back, a moment too long
            if (m.getState() != VerityMonster.STATE_PEER) {
                m.setState(VerityMonster.STATE_PEER);
                m.getNavigation().stop();
                m.getLookControl().setLookAt(p, 60f, 60f);
                story.sound(m, ModSounds.GROWL.get(), 0.8f, 0.5f);
            }
            return;
        }
        Vec3 target = route.get(waypoint % route.size());
        boolean there = m.position().distanceTo(target) < 1.8;
        if (!there && m.getState() != VerityMonster.STATE_WALK) m.setState(VerityMonster.STATE_WALK);
        if (there) {
            // at the glass: down on all fours, his face level with the window, looking in
            if (arrivedAt < 0) { arrivedAt = now; m.getNavigation().stop(); m.setState(VerityMonster.STATE_PEER); }
            if (now - arrivedAt > 120) { waypoint++; arrivedAt = -1; }       // stand there six seconds, looking in
            else if (home != null) m.getLookControl().setLookAt(home.getX() + 0.5, home.getY() + 1.5, home.getZ() + 0.5);
        } else if (now % 10 == 0) {
            arrivedAt = -1;
            m.getNavigation().moveTo(target.x, target.y, target.z, 0.75);
        }
        if (now % 20 == 0) {
            ServerLevel level = (ServerLevel) m.level();
            for (BlockPos b : BlockPos.betweenClosed(m.blockPosition().offset(-3, -1, -3), m.blockPosition().offset(3, 2, 3))) {
                BlockState st = level.getBlockState(b);
                if (st.getBlock() instanceof DoorBlock door && st.is(BlockTags.WOODEN_DOORS) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !st.getValue(DoorBlock.OPEN)) {
                    level.playSound(null, b, ModSounds.KNOCK.get(), SoundSource.BLOCKS, 1.6f, 0.8f);
                    BlockPos bb = b.immutable();
                    story.later(25, () -> {
                        BlockState s2 = level.getBlockState(bb);
                        if (s2.getBlock() instanceof DoorBlock d2) d2.setOpen(m, level, s2, bb, true);
                    });
                    break;
                }
            }
            if (--snuffCooldown <= 0) { snuffNear(level, m.blockPosition(), 5, 1); snuffCooldown = 3; }
        }
    }

    /** He reached you. It hurts; it is not (unless the config says so) the end. Then he is done for tonight. */
    private void strike(VerityMonster m, ServerPlayer p) {
        if (p.isCreative()) { phase = 1; phaseAt = story.ticks(); return; }
        m.setState(VerityMonster.STATE_SCREAM);
        m.getNavigation().stop();
        story.sound(m, ModSounds.JUMPSCARE.get(), 4f, 1f);
        story.fx(p, FxPayload.JUMPSCARE, 1f, 22);
        story.fx(p, FxPayload.SHAKE, 1f, 40);
        float dmg = VerityConfig.MONSTER_KILLS.get() ? 12f : Math.min(10f, Math.max(0f, p.getHealth() - 2f));
        if (dmg > 0) p.hurt(p.damageSources().mobAttack(m), dmg);
        Vec3 away = p.position().subtract(m.position()).multiply(1, 0, 1).normalize();
        p.push(away.x * 1.4, 0.55, away.z * 1.4);
        p.hurtMarked = true;
        story.addSanity(12f);
        story.note("ты настиг игрока по-настоящему и ударил его; потом ушёл в темноту");
        dev.verity.VerityMod.LOGGER.info("Verity stalker: struck {} ({} damage)", p.getGameProfile().getName(), dmg);
        phase = 1;
        phaseAt = story.ticks();
    }

    /** Done: he stands a moment, then walks off; out of everyone's sight he is simply not there any more. */
    private void leave(VerityMonster m, ServerPlayer p, long now) {
        long t = now - phaseAt;
        if (t < 30) return;
        if (t == 30 || m.getState() != VerityMonster.STATE_WALK) {
            Vec3 away = m.position().subtract(p.position()).multiply(1, 0, 1).normalize().scale(30);
            m.setState(VerityMonster.STATE_WALK);
            m.getNavigation().moveTo(m.getX() + away.x, m.getY(), m.getZ() + away.z, 0.9);
        }
        if (!observed(m) && (m.distanceTo(p) > 20 || t > 120) || t > 400) vanish(m);
    }

    void vanish(@Nullable VerityMonster m) {
        if (m != null) {
            dev.verity.VerityMod.LOGGER.info("Verity stalker: gone ({} ticks)", story.ticks() - startedAt);
            ((ServerLevel) m.level()).sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 2, m.getZ(), 30, 0.4, 1.4, 0.4, 0.02);
            m.discard();
        }
        monsterId = null;
        phase = 0;
    }

    // ------------------------------------------------------------------ the lights
    /** Torches near the player go out, one after another, with a hiss. They are written down and come back at dawn. */
    int snuffAround(ServerPlayer p, int radius, int max) {
        ServerLevel level = story.overworld();
        List<BlockPos> lit = new ArrayList<>();
        for (BlockPos b : BlockPos.betweenClosed(p.blockPosition().offset(-radius, -3, -radius), p.blockPosition().offset(radius, 4, radius))) {
            BlockState st = level.getBlockState(b);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LANTERN)) lit.add(b.immutable());
        }
        java.util.Collections.shuffle(lit, new java.util.Random(level.random.nextLong()));
        for (int i = 0; i < Math.min(max, lit.size()); i++) {
            BlockPos b = lit.get(i);
            story.later(i * 18, () -> snuff(level, b));
        }
        return Math.min(max, lit.size());
    }

    private void snuffNear(ServerLevel level, BlockPos center, int radius, int max) {
        int n = 0;
        for (BlockPos b : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 3, radius))) {
            if (n >= max) break;
            BlockState st = level.getBlockState(b);
            if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LANTERN)) { snuff(level, b.immutable()); n++; }
        }
    }

    private void snuff(ServerLevel level, BlockPos b) {
        BlockState st = level.getBlockState(b);
        if (!(st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LANTERN))) return;
        story.snuffed.add(b.asLong() + "|" + net.minecraft.world.level.block.Block.getId(st));
        level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, b, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.7f, 0.8f);
        level.sendParticles(ParticleTypes.SMOKE, b.getX() + 0.5, b.getY() + 0.6, b.getZ() + 0.5, 8, 0.1, 0.1, 0.1, 0.01);
        story.setDirty();
    }

    /** Morning: every light he put out is back where it was (unless something else stands there now). */
    void restoreLights() {
        if (story.snuffed.isEmpty()) return;
        ServerLevel level = story.overworld();
        for (String s : story.snuffed) {
            String[] parts = s.split("\\|");
            try {
                BlockPos b = BlockPos.of(Long.parseLong(parts[0]));
                BlockState st = net.minecraft.world.level.block.Block.stateById(Integer.parseInt(parts[1]));
                if (level.getBlockState(b).isAir() && st.canSurvive(level, b)) level.setBlock(b, st, 3);
            } catch (Exception ignored) {
            }
        }
        story.snuffed.clear();
        story.setDirty();
    }

    /**
     * The house's windows and doors: glass or a wooden door within 12 blocks of home with open sky on one side and a roof
     * on the other. For each, the spot a step and a half outside it, on the ground.
     */
    private static List<Vec3> openings(ServerLevel level, BlockPos home) {
        List<Vec3> out = new ArrayList<>();
        for (BlockPos b : BlockPos.betweenClosed(home.offset(-12, -3, -12), home.offset(12, 4, 12))) {
            BlockState st = level.getBlockState(b);
            boolean glass = st.is(net.minecraft.tags.BlockTags.IMPERMEABLE) || st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
            boolean door = st.is(BlockTags.WOODEN_DOORS) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
            if (!glass && !door) continue;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos outside = b.relative(dir), inside = b.relative(dir.getOpposite());
                if (!level.getBlockState(outside).isAir() || !level.canSeeSky(outside) || level.canSeeSky(inside)) continue;
                BlockPos g = ground(level, b.getX() + 0.5 + dir.getStepX() * 2.8, b.getZ() + 0.5 + dir.getStepZ() * 2.8);
                if (g == null || Math.abs(g.getY() - b.getY()) > 3) continue;
                Vec3 spot = new Vec3(b.getX() + 0.5 + dir.getStepX() * 2.8, g.getY(), b.getZ() + 0.5 + dir.getStepZ() * 2.8);
                if (out.stream().noneMatch(v -> v.distanceToSqr(spot) < 9)) out.add(spot);
            }
            if (out.size() >= 8) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private boolean observed(VerityMonster m) {
        for (ServerPlayer q : nearby(m, 96)) if (looksAt(q, m)) return true;
        return false;
    }

    private static boolean looksAt(ServerPlayer q, VerityMonster m) {
        if (q.isSpectator()) return false;
        Vec3 to = m.position().add(0, m.getState() == VerityMonster.STATE_PEER ? 1.4 : 3.0, 0).subtract(q.getEyePosition());
        double dist = to.length();
        double dot = q.getLookAngle().dot(to.normalize());
        return dot > (dist < 12 ? 0.55 : 0.8) && q.hasLineOfSight(m);
    }

    private List<ServerPlayer> nearby(VerityMonster m, double r) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer q : story.server().getPlayerList().getPlayers()) if (q.level() == m.level() && q.distanceTo(m) < r) out.add(q);
        return out;
    }

    @Nullable
    private static BlockPos ground(ServerLevel level, double x, double z) {
        BlockPos s = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(x, 0, z));
        if (!level.getFluidState(s.below()).isEmpty() || !level.getFluidState(s).isEmpty()) return null;
        if (!level.getBlockState(s.above()).isAir() || !level.getBlockState(s.above(2)).isAir()) return null;
        return s;
    }

    /** Leftovers from a session that closed mid-encounter. */
    void sweep(ServerLevel level) {
        for (VerityMonster m : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(VerityMonster.class), e -> e.getTags().contains(TAG))) {
            if (!m.getUUID().equals(monsterId)) m.discard();
        }
    }
}
