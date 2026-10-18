package dev.verity.registry;

import dev.verity.VerityMod;
import dev.verity.item.VerityItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(VerityMod.MODID);
    public static final DeferredItem<VerityItem> VERITY = REGISTER.register("verity", VerityItem::new);

    private ModItems() {}
}
