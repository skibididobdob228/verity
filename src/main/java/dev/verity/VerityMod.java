package dev.verity;

import com.mojang.logging.LogUtils;
import dev.verity.command.VerityCommand;
import dev.verity.net.Net;
import dev.verity.registry.ModEntities;
import dev.verity.registry.ModItems;
import dev.verity.registry.ModSounds;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(VerityMod.MODID)
public class VerityMod {
    public static final String MODID = "verity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VerityMod(IEventBus modBus, ModContainer container) {
        ModEntities.REGISTER.register(modBus);
        ModSounds.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        modBus.addListener(ModEntities::attributes);
        modBus.addListener(Net::register);
        container.registerConfig(ModConfig.Type.COMMON, VerityConfig.COMMON);
        container.registerConfig(ModConfig.Type.CLIENT, VerityConfig.CLIENT);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> VerityCommand.register(e.getDispatcher()));
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static boolean isClient() { return net.neoforged.fml.loading.FMLEnvironment.dist.isClient(); }

    /** How far gone he is, as this client knows it (client only: it touches the client's screen state). */
    public static float clientCorruption() {
        int st = dev.verity.client.fx.ScreenFx.stage;
        return st >= 5 && st <= 8 ? 1f : dev.verity.client.fx.ScreenFx.corruption;
    }
}
