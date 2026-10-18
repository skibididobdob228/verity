package dev.verity.registry;

import dev.verity.VerityMod;
import dev.verity.entity.VerityBall;
import dev.verity.entity.VerityBox;
import dev.verity.entity.VerityMonster;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(Registries.ENTITY_TYPE, VerityMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<VerityBall>> BALL = REGISTER.register("verity",
            () -> EntityType.Builder.of(VerityBall::new, MobCategory.MISC).sized(0.5f, 0.5f).eyeHeight(0.25f)
                    .fireImmune().clientTrackingRange(12).updateInterval(1).build("verity"));

    // Small collision box so it can follow the player anywhere; the 6.5 m body is purely visual and folds itself under low ceilings.
    public static final DeferredHolder<EntityType<?>, EntityType<VerityMonster>> MONSTER = REGISTER.register("monster",
            () -> EntityType.Builder.of(VerityMonster::new, MobCategory.MISC).sized(0.8f, 1.9f).eyeHeight(1.7f)
                    .fireImmune().clientTrackingRange(16).updateInterval(1).build("monster"));

    public static final DeferredHolder<EntityType<?>, EntityType<VerityBox>> BOX = REGISTER.register("box",
            () -> EntityType.Builder.<VerityBox>of(VerityBox::new, MobCategory.MISC).sized(0.8f, 0.8f)
                    .fireImmune().clientTrackingRange(10).updateInterval(2).build("box"));

    public static final DeferredHolder<EntityType<?>, EntityType<dev.verity.entity.VerityFriend>> FRIEND = REGISTER.register("friend",
            () -> EntityType.Builder.of(dev.verity.entity.VerityFriend::new, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f)
                    .clientTrackingRange(10).build("friend"));

    public static void attributes(EntityAttributeCreationEvent event) {
        event.put(FRIEND.get(), dev.verity.entity.VerityFriend.attributes().build());
        event.put(BALL.get(), VerityBall.attributes().build());
        event.put(MONSTER.get(), VerityMonster.attributes().build());
    }

    private ModEntities() {}
}
