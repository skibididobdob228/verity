package dev.verity.entity;

import dev.verity.VerityConfig;
import dev.verity.net.FxPayload;
import dev.verity.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Verity's true form: 6.5 metres of something that only wanted you to stay. */
public class VerityMonster extends PathfinderMob {
    /** PEER is WATCH brought down on all fours, face level with a doorway or a bed. */
    /** WALK: going somewhere he has been sent (around the house, away into the dark), not after anyone. */
    public static final int STATE_WATCH = 0, STATE_HUNT = 1, STATE_SCREAM = 2, STATE_CALM = 3, STATE_RISE = 4, STATE_PEER = 5, STATE_WALK = 6;

    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(VerityMonster.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATE_TICKS = SynchedEntityData.defineId(VerityMonster.class, EntityDataSerializers.INT);

    public VerityMonster(EntityType<? extends VerityMonster> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 100.0).add(Attributes.MOVEMENT_SPEED, 0.34)
                .add(Attributes.FOLLOW_RANGE, 96.0).add(Attributes.KNOCKBACK_RESISTANCE, 1.0).add(Attributes.STEP_HEIGHT, 1.1);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, STATE_WATCH);
        builder.define(STATE_TICKS, 0);
    }

    public int getState() { return entityData.get(STATE); }
    /** Ticks spent in the current state (synced so the client can time one-shot animations). */
    public int getStateTicks() { return entityData.get(STATE_TICKS); }

    public void setState(int state) {
        if (state != getState()) {
            entityData.set(STATE, state);
            entityData.set(STATE_TICKS, 0);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) entityData.set(STATE_TICKS, getStateTicks() + 1);
    }

    // ------------------------------------------------------------------ the hunt
    private int stuckTicks, stepTimer, growlTimer = 80;
    @javax.annotation.Nullable
    private java.util.UUID prey;

    /** Who he is after. Everyone else is only in the way. */
    public void setPrey(@javax.annotation.Nullable java.util.UUID id) { prey = id; }
    private Vec3 lastProgressPos = Vec3.ZERO;

    @Override
    protected void customServerAiStep() {
        Player target = prey == null ? null : level().getPlayerByUUID(prey);
        if (target == null || !target.isAlive() || target.isSpectator()) target = level().getNearestPlayer(this, 160.0);
        if (target == null) return;
        if (getState() == STATE_WALK) {                          // steered from outside: keep the path, just sound like it
            double moved = position().distanceTo(new Vec3(xo, yo, zo));
            if (moved > 0.05 && onGround() && --stepTimer <= 0) {
                stepTimer = 12;
                level().playSound(null, getX(), getY(), getZ(), ModSounds.STEP.get(), SoundSource.HOSTILE, 1.3f, 0.75f + random.nextFloat() * 0.15f);
            }
            return;
        }
        getLookControl().setLookAt(target, 40f, 40f);
        if (getState() != STATE_HUNT || target.isSpectator()) {
            getNavigation().stop();
            // while he only watches, he still turns to follow you
            double dx = target.getX() - getX(), dz = target.getZ() - getZ();
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
            setYRot(Mth.rotLerp(0.1f, getYRot(), yaw));
            yBodyRot = getYRot();
            return;
        }
        double dist = distanceTo(target);
        // he cannot swim: prey standing in water is out of reach, and he knows it
        if (target.isInWater() && dist < 12) {
            getNavigation().stop();
            return;
        }
        // slow to start, relentless once you have a lead
        double speed = getStateTicks() < 80 ? 0.85 : dist > 26 ? 1.3 : dist > 12 ? 1.12 : 1.0;
        if (tickCount % 5 == 0) getNavigation().moveTo(target, speed);

        // footsteps you can feel
        double moved = position().distanceTo(new Vec3(xo, yo, zo));
        if (moved > 0.05 && onGround() && --stepTimer <= 0) {
            stepTimer = 9;
            level().playSound(null, getX(), getY(), getZ(), ModSounds.STEP.get(), SoundSource.HOSTILE, 2.2f, 0.8f + random.nextFloat() * 0.2f);
            for (Player p : level().players()) {
                double d = p.distanceTo(this);
                if (d < 20 && p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new FxPayload(FxPayload.SHAKE, (float) (0.5 * (1 - d / 20)), 6));
            }
        }
        if (--growlTimer <= 0) {
            growlTimer = 100 + random.nextInt(140);
            level().playSound(null, getX(), getY(), getZ(), ModSounds.GROWL.get(), SoundSource.HOSTILE, 2.5f, 0.85f + random.nextFloat() * 0.3f);
        }

        // nothing you build will keep him out
        if (tickCount % 10 == 0) {
            if (position().distanceTo(lastProgressPos) < 0.6) stuckTicks += 10; else { stuckTicks = 0; lastProgressPos = position(); }
            if (stuckTicks >= 10 && dist > 1.8) smashTowards(target);
            if (stuckTicks >= 160 && dist > 6) {
                relocateBehind(target);
                stuckTicks = 0;
            }
        }
    }

    /**
     * Walls, doors, glass, a hole filled in behind you: he goes through. Everything that can be mined gives way except the
     * very hardest blocks (obsidian, and what cannot be mined at all). The broken blocks drop as if mined.
     */
    private void smashTowards(Player target) {
        if (!VerityConfig.MONSTER_BREAKS_BLOCKS.get()) return;
        Vec3 dir = target.position().subtract(position()).multiply(1, 0, 1).normalize();
        boolean up = target.getY() > getY() + 1.5, down = target.getY() < getY() - 1.5;
        boolean broke = false;
        for (int step = 1; step <= 2; step++) {
            for (int dy = down ? -1 : 0; dy <= (up ? 4 : 3); dy++) {
                for (double side : new double[] {0, -0.6, 0.6}) {
                    BlockPos pos = BlockPos.containing(getX() + dir.x * step - dir.z * side, getY() + dy + 0.1, getZ() + dir.z * step + dir.x * side);
                    BlockState state = level().getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                    float hardness = state.getDestroySpeed(level(), pos);
                    if (hardness >= 0 && hardness < 40f && !state.is(BlockTags.WITHER_IMMUNE)) {
                        level().destroyBlock(pos, true, this);
                        broke = true;
                    }
                }
            }
        }
        if (broke) {
            stuckTicks = 0;
            level().playSound(null, getX(), getY(), getZ(), ModSounds.GROWL.get(), SoundSource.HOSTILE, 1.5f, 0.6f);
            for (Player p : level().players()) {
                if (p.distanceTo(this) < 24 && p instanceof ServerPlayer sp) PacketDistributor.sendToPlayer(sp, new FxPayload(FxPayload.SHAKE, 0.7f, 12));
            }
        }
    }

    /** If the terrain truly stops him he simply is somewhere else: behind you. */
    private void relocateBehind(Player target) {
        Vec3 back = Vec3.directionFromRotation(0, target.getYRot() + 180f + random.nextInt(80) - 40);
        for (int r = 14; r >= 8; r -= 2) {
            BlockPos pos = level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.containing(target.getX() + back.x * r, target.getY(), target.getZ() + back.z * r));
            if (Math.abs(pos.getY() - target.getY()) < 6 && level().getFluidState(pos.below()).isEmpty()) {
                teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                getNavigation().stop();
                return;
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && source.getEntity() == null && super.hurt(source, amount);
    }

    @Override public boolean isInvulnerableTo(DamageSource source) { return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY); }
    @Override public boolean isPushable() { return false; }
    @Override protected void doPush(Entity entity) {}
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }

    /** The visible body is far bigger than the collision box. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(4.0, 0, 4.0).expandTowards(0, 6.0, 0);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("VerityState", getState());
        if (prey != null) tag.putUUID("VerityPrey", prey);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(STATE, tag.getInt("VerityState"));
        if (tag.hasUUID("VerityPrey")) prey = tag.getUUID("VerityPrey");
    }
}
