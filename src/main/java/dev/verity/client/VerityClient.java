package dev.verity.client;

import dev.verity.VerityMod;
import dev.verity.client.fx.Hud;
import dev.verity.client.fx.ScreenFx;
import dev.verity.client.render.BallRenderer;
import dev.verity.client.render.BoxRenderer;
import dev.verity.client.render.MonsterRenderer;
import dev.verity.client.render.VerityItemRenderer;
import dev.verity.registry.ModItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import dev.verity.client.selftest.SelfTest;
import dev.verity.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = VerityMod.MODID, dist = Dist.CLIENT)
public class VerityClient {
    public VerityClient(IEventBus modBus, ModContainer container) {
        modBus.addListener((EntityRenderersEvent.RegisterRenderers e) -> {
            e.registerEntityRenderer(ModEntities.BALL.get(), BallRenderer::new);
            e.registerEntityRenderer(ModEntities.MONSTER.get(), MonsterRenderer::new);
            e.registerEntityRenderer(ModEntities.BOX.get(), BoxRenderer::new);
            e.registerEntityRenderer(ModEntities.FRIEND.get(), dev.verity.client.render.FriendRenderer::new);
        });
        modBus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private VerityItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new VerityItemRenderer();          // not before the game has its render dispatchers
                return renderer;
            }
        }, ModItems.VERITY.get()));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent e) -> e.register(dev.verity.client.voice.VoiceInput.TALK));
        modBus.addListener((RegisterGuiLayersEvent e) -> {
            e.registerBelowAll(VerityMod.id("night"), Hud::renderNight);
            e.registerAboveAll(VerityMod.id("overlay"), Hud::render);
        });
        NeoForge.EVENT_BUS.addListener(ScreenFx::onTick);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.voice.VoiceInput::onTick);
        NeoForge.EVENT_BUS.addListener(CityConsent::onTick);
        NeoForge.EVENT_BUS.addListener(FourthWall::onTick);
        NeoForge.EVENT_BUS.addListener(FourthWall::onScreen);
        NeoForge.EVENT_BUS.addListener(FourthWall::onLogout);
        NeoForge.EVENT_BUS.addListener(FourthWall::onScreenRender);
        NeoForge.EVENT_BUS.addListener(ScreenFx::onCamera);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.fx.Soundtrack::onTick);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.fx.Soundtrack::onSelectMusic);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.fx.Sanity::onTick);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.fx.Sanity::onCamera);
        NeoForge.EVENT_BUS.addListener(dev.verity.client.fx.Sanity::onFov);
        NeoForge.EVENT_BUS.addListener(ScreenFx::onFog);
        NeoForge.EVENT_BUS.addListener(ScreenFx::onFogColor);
        SelfTest.init();
    }
}
