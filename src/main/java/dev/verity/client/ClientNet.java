package dev.verity.client;

import dev.verity.client.fx.Hud;
import dev.verity.client.fx.ScreenFx;
import dev.verity.client.voice.Voice;
import dev.verity.net.FxPayload;
import dev.verity.net.SayPayload;
import dev.verity.net.StatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Client side of the mod's packets. Only ever touched on a physical client. */
public final class ClientNet {
    public static void onSay(SayPayload p) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null) return;
            Entity e = mc.level.getEntity(p.entityId());
            if (e == null) return;
            float volume = switch (p.profile()) { case "monster" -> 5f; case "rage" -> 3f; case "box" -> 1.3f; default -> 1.8f; };
            // "@name": spoken live in a borrowed voice, and signed with that name
            boolean borrowed = p.lineId().startsWith("@");
            String line = borrowed ? "" : p.lineId(), who = borrowed ? p.lineId().substring(1) : "";
            Voice.say(e, line, p.text(), p.profile(), dev.verity.entity.Emotion.byId(p.emotion()).name(), volume, seconds -> Hud.subtitle(p.text(), p.profile(), seconds, who));
        });
    }

    public static void onFx(FxPayload p) {
        Minecraft.getInstance().execute(() -> ScreenFx.trigger(p.kind(), p.amount(), p.ticks()));
    }

    public static void onGuide(dev.verity.net.GuidePayload p) {
        Minecraft.getInstance().execute(() -> dev.verity.client.fx.Guide.set(p));
    }

    public static void onState(StatePayload p) {
        Minecraft.getInstance().execute(() -> ScreenFx.setState(p));
    }

    private ClientNet() {}
}
