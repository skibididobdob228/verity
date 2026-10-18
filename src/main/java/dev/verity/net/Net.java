package dev.verity.net;

import dev.verity.client.ClientNet;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Net {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1").optional();
        // the lambdas keep the client-only class from loading on a dedicated server
        r.playToClient(SayPayload.TYPE, SayPayload.CODEC, (p, ctx) -> ClientNet.onSay(p));
        r.playToClient(FxPayload.TYPE, FxPayload.CODEC, (p, ctx) -> ClientNet.onFx(p));
        r.playToServer(CityPayload.TYPE, CityPayload.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) { dev.verity.story.Story st = dev.verity.story.Story.get(sp.serverLevel()); st.setCity(sp, p.city()); st.setApps(sp, p.apps()); st.setName(sp, p.name()); st.setConsent(sp); }
        }));
        r.playToClient(GuidePayload.TYPE, GuidePayload.CODEC, (p, ctx) -> ClientNet.onGuide(p));
        r.playToServer(VoiceAskPayload.TYPE, VoiceAskPayload.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) dev.verity.story.Story.get(sp.serverLevel()).onVoice(sp, p.text());
        }));
        r.playToClient(StatePayload.TYPE, StatePayload.CODEC, (p, ctx) -> ClientNet.onState(p));
    }

    private Net() {}
}
