package dev.verity.item;

import dev.verity.entity.VerityBall;
import dev.verity.story.Story;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * Verity as you carry him: a ball in your inventory. He talks from there, and if you throw him away he lands
 * as a {@link VerityBall} and finds his way back. There is only ever one real Verity per world: each time he
 * re-materialises the story bumps a serial number, and any older copy (left in a chest, duplicated in creative)
 * quietly stops existing.
 */
public class VerityItem extends Item {
    private static final String SERIAL = "VeritySerial";

    public VerityItem() {
        super(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.EPIC));
    }

    public static ItemStack create(Item item, int serial) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putInt(SERIAL, serial);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static int serial(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || !data.contains(SERIAL) ? -1 : data.copyTag().getInt(SERIAL);
    }

    /** The label on the product. Read it after the countdown and it reads differently. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        boolean ru = !net.minecraft.locale.Language.getInstance().getOrDefault("item.verity.verity").equals("Verity™");
        boolean dark = dev.verity.VerityMod.isClient() && dev.verity.VerityMod.clientCorruption() > 0.6f;
        net.minecraft.ChatFormatting y = net.minecraft.ChatFormatting.GOLD, g = net.minecraft.ChatFormatting.DARK_GRAY;
        tooltip.add(net.minecraft.network.chat.Component.literal(ru ? "Личный друг-помощник" : "Your personal helper friend").withStyle(y));
        tooltip.add(net.minecraft.network.chat.Component.literal(dark ? (ru ? "Знает всё™. Всё про тебя™." : "Knows everything™. About you™.")
                : (ru ? "Знает всё™" : "Knows everything™")).withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.ITALIC));
        int serial = serial(stack);
        tooltip.add(net.minecraft.network.chat.Component.literal((ru ? "Модель V-1 · экземпляр №" : "Model V-1 · unit #") + String.format("%05d", Math.max(serial, 0) * 37 + 1931)).withStyle(g));
        tooltip.add(net.minecraft.network.chat.Component.literal(dark ? (ru ? "Возврату не подлежит. Никогда." : "Non-returnable. Ever.")
                : (ru ? "Verity™ — зарегистрированная торговая марка." : "Verity™ is a registered trademark.")).withStyle(dark ? net.minecraft.ChatFormatting.DARK_RED : g));
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level instanceof ServerLevel sl && entity.tickCount % 20 == 0 && !Story.get(sl).isCurrent(stack)) stack.shrink(1);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) Story.get(sl).onItemUsed(sp);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /** Right-click on a block: put him down there. He stays until you pick him up (or walk away without him). */
    @Override
    public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext ctx) {
        if (ctx.getLevel() instanceof ServerLevel sl && ctx.getPlayer() instanceof ServerPlayer sp && Story.get(sl).isCurrent(ctx.getItemInHand())) {
            net.minecraft.core.BlockPos at = ctx.getClickedPos().relative(ctx.getClickedFace());
            Story.get(sl).placeAt(sp, new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5));
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    // Thrown away, spilled on death, knocked out of a broken chest: he does not lie there as an item, he sits there as himself.
    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return true;
    }

    @Override
    public Entity createEntity(Level level, Entity location, ItemStack stack) {
        return level instanceof ServerLevel sl ? Story.get(sl).onItemDropped(sl, location, stack) : null;
    }
}
