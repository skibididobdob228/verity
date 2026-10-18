package dev.verity.story;

import dev.verity.VerityMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Feeds what happens in the world into the story. */
@EventBusSubscriber(modid = VerityMod.MODID)
public final class StoryEvents {
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        Story.get(event.getServer().overworld()).tick();
    }

    @SubscribeEvent
    static void onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        String text = event.getRawText();
        // answer after the player's own line has reached the chat
        Story story = Story.get(p.serverLevel());
        story.later(2, () -> story.onChat(p, text));
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Story.get(p.serverLevel()).onLogin(p);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Story.get(p.serverLevel()).onLogout(p);
    }

    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Story.get(p.serverLevel()).onBlockPlacedNear(p, event.getPos());
    }

    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getTarget() instanceof AbstractVillager) Story.get(p.serverLevel()).onVillagerTouched(p);
    }

    @SubscribeEvent
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            Lab.onUse(Story.get(p.serverLevel()), p, event.getPos());
        }
    }

    @SubscribeEvent
    static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Story.get(p.serverLevel()).onDimensionTravel(p)) event.setCanceled(true);
    }

    @SubscribeEvent
    static void onSleep(CanPlayerSleepEvent event) {
        ServerPlayer p = event.getEntity();
        var reason = Story.get(p.serverLevel()).onTrySleep(p);
        if (reason != null) {
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
            p.displayClientMessage(reason, true);
        }
    }

    // ------------------------------------------------------------------ what he "notices" (goes into the AI's journal)

    /** Only once he exists, and only about his own person. */
    private static Story watching(net.minecraft.world.entity.Entity e) {
        if (!(e instanceof ServerPlayer p)) return null;
        Story story = Story.get(p.serverLevel());
        if (story.stage().ordinal() < Story.Stage.HELPER.ordinal()) return null;
        return story.ownerId() == null || story.ownerId().equals(p.getUUID()) ? story : null;
    }

    @SubscribeEvent
    static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        Story story = watching(event.getEntity());
        if (story != null) {
            story.note("игрок умер: " + event.getSource().getLocalizedDeathMessage(event.getEntity()).getString());
            return;
        }
        story = watching(event.getSource().getEntity());
        if (story != null && !(event.getEntity() instanceof dev.verity.entity.VerityBall)) {
            story.note("игрок убил: " + event.getEntity().getType().getDescription().getString());
        }
    }

    @SubscribeEvent
    static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        Story story = watching(event.getEntity());
        if (story == null || event.getNewDamage() < 1f) return;
        net.minecraft.world.entity.Entity by = event.getSource().getEntity();
        String cause = by != null ? by.getType().getDescription().getString() : switch (event.getSource().getMsgId()) {
            case "fall" -> "упал с высоты";
            case "drown" -> "тонет";
            case "inFire", "onFire", "hotFloor" -> "огонь";
            case "lava" -> "лава";
            case "starve" -> "голод";
            case "cactus", "sweetBerryBush" -> "укололся";
            case "inWall" -> "задыхается в блоке";
            case "freeze" -> "замерзает";
            case "lightningBolt" -> "молния";
            case "explosion", "explosion.player" -> "взрыв";
            case "magic", "indirectMagic" -> "магия";
            case "wither" -> "иссушение";
            case "outOfWorld" -> "падает в пустоту";
            default -> event.getSource().getMsgId();
        };
        float left = event.getEntity().getHealth();
        story.note("игрок получил урон (" + cause + ")" + (left <= 6f ? ", у него осталось мало здоровья" : ""));
    }

    @SubscribeEvent
    static void onAdvancement(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
        Story story = watching(event.getEntity());
        if (story == null) return;
        event.getAdvancement().value().display().ifPresent(d -> {
            if (d.shouldAnnounceChat()) story.note("игрок получил достижение «" + d.getTitle().getString() + "»");
        });
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        Story story = watching(event.getPlayer());
        if (story == null) return;
        net.minecraft.world.level.block.state.BlockState state = event.getState();
        if (state.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || state.is(net.minecraft.world.level.block.Blocks.SPAWNER)
                || state.is(net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS)) {
            story.note("игрок добыл: " + state.getBlock().getName().getString());
        }
    }

    @SubscribeEvent
    static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        Story story = watching(event.getEntity());
        if (story == null) return;
        net.minecraft.world.item.ItemStack made = event.getCrafting();
        if (made.isDamageableItem() || made.is(net.minecraft.tags.ItemTags.BEDS) || made.getItem() instanceof net.minecraft.world.item.BlockItem bi
                && bi.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) {
            story.note("игрок сделал: " + made.getHoverName().getString());
        }
    }

    @SubscribeEvent
    static void onWake(net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        // woken by the morning, not by something standing over the bed: that is rest, for anyone
        if (!event.wakeImmediately()) Story.get(p.serverLevel()).onRested(p);
        Story story = watching(p);
        if (story != null) story.note("игрок поспал в кровати");
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Story story = watching(event.getEntity());
        if (story != null) story.note("игрок перешёл в измерение " + event.getTo().location().getPath());
    }

    @SubscribeEvent
    static void onEaten(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getItem().has(net.minecraft.core.component.DataComponents.FOOD)) {
            Story.get(p.serverLevel()).onAte(p);                     // a meal steadies anyone a little
        }
    }

    private StoryEvents() {}
}
