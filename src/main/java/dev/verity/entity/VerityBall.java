package dev.verity.entity;

import dev.verity.registry.ModSounds;
import dev.verity.story.Story;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Verity out of your pocket: a small yellow ball lying on the ground, looking at you. He does not fly. He is
 * here only while he tumbles out of his box, after you throw him away, and in the moments before he changes;
 * the rest of the time he is the item in your inventory ({@link dev.verity.item.VerityItem}).
 */
public class VerityBall extends PathfinderMob {
    public static final int MODE_GROUND = 0, MODE_STAY = 1, MODE_TRANSFORM = 2, MODE_INTRO = 3;

    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(VerityBall.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Integer> EMOTION = SynchedEntityData.defineId(VerityBall.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> CORRUPTION = SynchedEntityData.defineId(VerityBall.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(VerityBall.class, EntityDataSerializers.INT);

    /** Showcase only (/verity anchor): hold him at a fixed spot in the air. */
    @Nullable
    private Vec3 anchor;
    private int hurtCooldown, modeTicks, hopTimer = 60;
    private double lastFall;
    /** Seconds spent lying on the ground; the story uses it to decide when he has waited long enough. */
    public int groundSeconds;

    public VerityBall(EntityType<? extends VerityBall> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
        builder.define(EMOTION, Emotion.HAPPY.ordinal());
        builder.define(CORRUPTION, 0f);
        builder.define(MODE, MODE_GROUND);
    }

    public void setOwner(@Nullable UUID id) { entityData.set(OWNER, Optional.ofNullable(id)); }
    @Nullable public UUID getOwnerId() { return entityData.get(OWNER).orElse(null); }
    public Emotion getEmotion() { return Emotion.byId(entityData.get(EMOTION)); }
    public void setEmotion(Emotion e) { entityData.set(EMOTION, e.ordinal()); }
    /** 0 = pristine helper, 1 = barely holding its shape. Drives the glitch effects on the client. */
    public float getCorruption() { return entityData.get(CORRUPTION); }
    public void setCorruption(float v) { entityData.set(CORRUPTION, Mth.clamp(v, 0f, 1f)); }
    public int getMode() { return entityData.get(MODE); }
    public void setMode(int mode) { entityData.set(MODE, mode); modeTicks = 0; }
    public void setAnchor(@Nullable Vec3 pos) { anchor = pos; }

    // ------------------------------------------------------------------ movement: gravity, a bounce, the odd hop

    @Override
    protected void customServerAiStep() {
        if (hurtCooldown > 0) hurtCooldown--;
        modeTicks++;
        if (tickCount % 20 == 0) groundSeconds = onGround() || isInWater() ? groundSeconds + 1 : 0;
        Player watcher = level().getNearestPlayer(this, 24.0);
        if (watcher != null) lookAt(watcher);
        Vec3 vel = getDeltaMovement();
        int mode = getMode();

        if (mode == MODE_STAY && anchor != null) {
            setNoGravity(true);
            vel = vel.scale(0.82).add(anchor.subtract(position()).scale(0.11));
            setDeltaMovement(vel);
            return;
        }
        setNoGravity(false);
        if (onGround() && lastFall < -0.13) {                   // landed hard enough to bounce
            vel = new Vec3(vel.x * 0.75, -lastFall * 0.55, vel.z * 0.75);
            playSound(ModSounds.BOUNCE.get(), 0.7f, 0.9f + random.nextFloat() * 0.3f);
        } else if (isInWater()) {
            vel = vel.add(0, 0.03, 0).scale(0.9);              // he bobs up rather than sink out of sight
        } else {
            vel = vel.add(0, -0.05, 0).multiply(onGround() ? 0.6 : 0.98, 0.985, onGround() ? 0.6 : 0.98);
        }
        if (onGround()) {
            if (mode == MODE_TRANSFORM) {                       // something is trying to get out
                vel = vel.add((random.nextDouble() - 0.5) * 0.12, random.nextInt(4) == 0 ? 0.16 : 0, (random.nextDouble() - 0.5) * 0.12);
            } else if (mode == MODE_GROUND && watcher != null && --hopTimer <= 0) {
                hopTimer = 50 + random.nextInt(90);             // a little hop: look, I am still here
                vel = vel.add(0, 0.2, 0);
            }
        }
        lastFall = vel.y;
        setDeltaMovement(vel);
        if (mode == MODE_INTRO && modeTicks > 60) setMode(MODE_GROUND);
        if (isInLava() && hurtCooldown == 0 && level() instanceof ServerLevel sl) {
            hurtCooldown = 100;
            Story.get(sl).onBallHurt(this, damageSources().lava());
        }
    }

    /** He is always looking at someone. */
    private void lookAt(Player player) {
        Vec3 eye = player.getEyePosition();
        double dx = eye.x - getX(), dy = eye.y - (getY() + 0.25), dz = eye.z - getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        setYRot(Mth.rotLerp(0.35f, getYRot(), yaw));
        setXRot(Mth.lerp(0.35f, getXRot(), pitch));
        yHeadRot = getYRot();
        yBodyRot = getYRot();
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (isControlledByLocalInstance()) move(MoverType.SELF, getDeltaMovement());
        calculateEntityAnimation(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel sl && getY() < level().getMinBuildHeight() - 8) Story.get(sl).onBallLost(this);
    }

    // ------------------------------------------------------------------ he cannot be hurt, pushed, leashed or lost

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && source.getEntity() == null && !level().isClientSide
                && getY() >= level().getMinBuildHeight()) {
            return super.hurt(source, amount);          // /kill still works
        }
        if (level() instanceof ServerLevel sl && hurtCooldown == 0) {
            hurtCooldown = 100;
            Story.get(sl).onBallHurt(this, source);
        }
        return false;
    }

    @Override public boolean isInvulnerableTo(DamageSource source) { return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY); }
    @Override public boolean isPushable() { return false; }
    @Override protected void doPush(Entity entity) {}
    @Override protected void pushEntities() {}
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override protected boolean shouldDespawnInPeaceful() { return false; }

    /** Right-click: pick him up. */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && level() instanceof ServerLevel sl) Story.get(sl).onBallPoked(this, player);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (getOwnerId() != null) tag.putUUID("VerityOwner", getOwnerId());
        tag.putInt("VerityEmotion", entityData.get(EMOTION));
        tag.putFloat("VerityCorruption", getCorruption());
        tag.putInt("VerityMode", getMode() == MODE_STAY ? MODE_GROUND : getMode());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("VerityOwner")) setOwner(tag.getUUID("VerityOwner"));
        entityData.set(EMOTION, tag.getInt("VerityEmotion"));
        setCorruption(tag.getFloat("VerityCorruption"));
        entityData.set(MODE, tag.getInt("VerityMode"));
    }
}
