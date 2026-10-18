package dev.verity.client.fx;

import com.mojang.math.Axis;
import dev.verity.net.GuidePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * The pointer he leaves on the screen after telling you where something is: his trademark, an arrow that turns as you
 * turn, the name of the place and how far it still is. It goes away when you get there or after a few minutes.
 */
public final class Guide {
    private static GuidePayload target;
    private static long until;
    private static String dimension = "";

    public static void set(GuidePayload p) {
        Minecraft mc = Minecraft.getInstance();
        target = p;
        until = System.currentTimeMillis() + p.seconds() * 1000L;
        dimension = mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    public static void render(GuiGraphics g, Font font, int w) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (target == null || pl == null || mc.level == null) return;
        if (System.currentTimeMillis() > until || !mc.level.dimension().location().toString().equals(dimension)) { target = null; return; }
        double dx = target.x() + 0.5 - pl.getX(), dz = target.z() + 0.5 - pl.getZ();
        int dist = (int) Math.sqrt(dx * dx + dz * dz);
        boolean knowY = target.y() != GuidePayload.NO_Y;
        if (dist < (knowY ? 4 : 12) && (!knowY || Math.abs(target.y() - pl.getY()) < 6)) { target = null; return; }
        float yaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        float rel = Mth.wrapDegrees(yaw - pl.getYRot());

        String text = "Верити™ · " + target.label() + " · " + dist + " бл." + (knowY && Math.abs(target.y() - pl.getY()) > 8 ? (target.y() < pl.getY() ? " ↓" : " ↑") + Math.abs(target.y() - (int) pl.getY()) : "");
        int tw = font.width(text), boxW = tw + 26, x0 = (w - boxW) / 2, y0 = 4;
        long left = until - System.currentTimeMillis();
        int alpha = (int) (Mth.clamp(left / 1500f, 0f, 1f) * 255);
        if (alpha < 8) return;
        g.fill(x0, y0, x0 + boxW, y0 + 15, (alpha * 0x70 / 255) << 24);
        g.fill(x0, y0 + 14, x0 + boxW, y0 + 15, (alpha << 24) | 0xEBCC05);
        g.drawString(font, text, x0 + 20, y0 + 4, (alpha << 24) | 0xFFFFFF, false);
        g.pose().pushPose();
        g.pose().translate(x0 + 10, y0 + 7.5f, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rel));
        g.drawString(font, "▲", -font.width("▲") / 2, -4, (alpha << 24) | 0xEBCC05, false);
        g.pose().popPose();
    }

    private Guide() {}
}
