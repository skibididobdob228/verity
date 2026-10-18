package dev.verity.entity;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** The parcel nobody ordered. Something inside keeps asking whether anyone is there. */
public class VerityBox extends Entity {
    public static final int OPEN_DURATION = 90;

    private static final EntityDataAccessor<Integer> OPEN_TICKS = SynchedEntityData.defineId(VerityBox.class, EntityDataSerializers.INT);

    @Nullable
    private UUID opener;
    /** A box in his store-room: it knocks, but it is not for you and it does not open. */
    private boolean decoy;

    public boolean isDecoy() { return decoy; }
    public void setDecoy(boolean value) { decoy = value; }

    public VerityBox(EntityType<? extends VerityBox> type, Level level) {
        super(type, level);
        blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OPEN_TICKS, -1);
    }

    /** -1 while closed, otherwise ticks since it was opened. */
    public int getOpenTicks() { return entityData.get(OPEN_TICKS); }
    public boolean isOpening() { return getOpenTicks() >= 0; }
    @Nullable public UUID getOpener() { return opener; }

    @Override
    public void tick() {
        super.tick();
        if (!isOpening()) {
            if (!isNoGravity()) setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
            move(MoverType.SELF, getDeltaMovement());
            setDeltaMovement(getDeltaMovement().scale(onGround() ? 0.5 : 0.98));
        } else {
            setDeltaMovement(Vec3.ZERO);
            if (!level().isClientSide) entityData.set(OPEN_TICKS, getOpenTicks() + 1);
        }
        if (!level().isClientSide && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            dev.verity.story.Story.get(sl).onBoxTick(this);
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!level().isClientSide) open(player);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    public void open(Player player) {
        if (isOpening() || decoy) return;
        opener = player.getUUID();
        entityData.set(OPEN_TICKS, 0);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean canBeCollidedWith() { return !isOpening(); }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(OPEN_TICKS, tag.contains("OpenTicks") ? tag.getInt("OpenTicks") : -1);
        if (tag.hasUUID("Opener")) opener = tag.getUUID("Opener");
        decoy = tag.getBoolean("Decoy");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("OpenTicks", getOpenTicks());
        if (opener != null) tag.putUUID("Opener", opener);
        tag.putBoolean("Decoy", decoy);
    }
}
