package dev.verity.client.fx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import dev.verity.client.render.FaceParams;
import dev.verity.client.render.FaceTexture;
import dev.verity.entity.Emotion;
import java.util.List;
import java.util.Random;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** The overlay: subtitles, the countdown in the corner, vignette, static, glitch bars, the jumpscare. */
public final class Hud {
    private static final ResourceLocation VIGNETTE = VerityMod.id("dynamic/vignette"), NOISE = VerityMod.id("dynamic/noise");
    private static final int NW = 96, NH = 54;
    private static DynamicTexture noise;
    private static FaceTexture scareFace;
    private static final Random RNG = new Random();
    private static boolean ready;
    private static int frame;

    private static String subText = "", subProfile = "ball", subName = "";
    private static long subStart;
    private static float subSeconds;

    public static void subtitle(String text, String profile, float seconds, String name) {
        subtitle(text, profile, seconds);
        subName = name;
    }

    public static void subtitle(String text, String profile, float seconds) {
        subName = "";
        if (!text.equals(subText) || System.nanoTime() - subStart > 2_000_000_000L) subStart = System.nanoTime();
        subText = text; subProfile = profile; subSeconds = seconds;
    }

    /** Drawn under the whole HUD: dims the world (and your hand) at night, leaves hotbar, chat and subtitles readable. */
    public static void renderNight(GuiGraphics g, DeltaTracker delta) {
        float dim = ScreenFx.nightDim();
        if (dim > 0.01f && Minecraft.getInstance().level != null) g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (dim * 255) << 24);
    }

    private static void init() {
        ready = true;
        Minecraft mc = Minecraft.getInstance();
        NativeImage v = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float dx = (x - 31.5f) / 31.5f, dy = (y - 31.5f) / 31.5f;
                float a = Mth.clamp((float) (Math.sqrt(dx * dx + dy * dy) - 0.45f) / 0.75f, 0f, 1f);
                v.setPixelRGBA(x, y, (int) (a * a * 255) << 24);
            }
        }
        DynamicTexture vt = new DynamicTexture(v);
        vt.setFilter(true, false);
        mc.getTextureManager().register(VIGNETTE, vt);
        noise = new DynamicTexture(NW, NH, true);
        mc.getTextureManager().register(NOISE, noise);
        scareFace = new FaceTexture();
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui && ScreenFx.jumpscare <= 0 && ScreenFx.blackout <= 0 && subText.isEmpty()
                && ScreenFx.staticLevel <= 0 && ScreenFx.darkness < 0.05f && ScreenFx.dread < 0.05f && ScreenFx.night < 0.05f) return;
        if (!ready) init();
        frame++;
        int w = g.guiWidth(), h = g.guiHeight();
        float fxK = VerityConfig.FX_INTENSITY.get().floatValue();
        RenderSystem.enableBlend();

        // the dark at the edges of your vision
        float vig = Mth.clamp(ScreenFx.dread * 0.55f + ScreenFx.darkness * 0.6f + ScreenFx.proximity * 0.5f, 0f, 1f) * Math.min(fxK, 1f);
        vig = Math.max(vig, ScreenFx.night * (1f - 0.5f * ScreenFx.lightRelief) * 0.85f);                 // at night the corners of your eyes go first
        vig = Math.max(vig, Sanity.level() * 0.13f * Math.min(fxK, 1f));
        if (vig > 0.02f) {
            g.setColor(1f, 1f, 1f, vig);
            g.blit(VIGNETTE, 0, 0, 0f, 0f, w, h, w, h);
            g.setColor(1f, 1f, 1f, 1f);
        }

        madness(g, w, h);
        RenderSystem.enableBlend();                 // a fill switches blending off behind itself; the static below needs it

        // static
        float st = Mth.clamp(ScreenFx.staticLevel + ScreenFx.proximity * 0.12f, 0f, 1f);
        if (st > 0.02f) {
            if (frame % 2 == 0) {
                NativeImage img = noise.getPixels();
                for (int y = 0; y < NH; y++) {
                    boolean band = RNG.nextInt(9) == 0;
                    for (int x = 0; x < NW; x++) {
                        int v = RNG.nextInt(256);
                        img.setPixelRGBA(x, y, (band ? 255 : RNG.nextInt(190)) << 24 | v << 16 | v << 8 | v);
                    }
                }
                noise.upload();
            }
            g.setColor(1f, 1f, 1f, st * 0.6f);
            g.blit(NOISE, 0, 0, 0f, 0f, w, h, w, h);
            g.setColor(1f, 1f, 1f, 1f);
        }

        // glitch: torn bars of wrong colour
        if (ScreenFx.glitch > 0.02f) {
            int bars = 2 + (int) (ScreenFx.glitch * 7);
            for (int i = 0; i < bars; i++) {
                int y = RNG.nextInt(h), bh = 1 + RNG.nextInt(Math.max(2, h / 22)), x = RNG.nextInt(w / 2) - w / 4, bw = w / 3 + RNG.nextInt(w);
                int c = switch (RNG.nextInt(4)) { case 0 -> 0x55EBCC05; case 1 -> 0x40101010; case 2 -> 0x30FF2020; default -> 0x3020F0F0; };
                g.fill(x, y, x + bw, y + bh, c);
            }
        }

        if (ScreenFx.flash > 0.02f) g.fill(0, 0, w, h, (int) (Mth.clamp(ScreenFx.flash, 0f, 1f) * 200) << 24 | 0xFFFFFF);

        float black = ScreenFx.blackoutAmount();
        if (black > 0.01f) g.fill(0, 0, w, h, (int) (black * 255) << 24);

        if (ScreenFx.jumpscare > 0) jumpscare(g, w, h);

        countdown(g, mc.font, w);
        if (!mc.options.hideGui) Guide.render(g, mc.font, w);
        MicHud.render(g, mc.font, w, h);
        sanityMarks(g, mc);
        subtitles(g, mc.font, w, h);
        if (dev.verity.client.FourthWall.crashTicks > 0) fakeDisconnect(g, mc.font, w, h);
        RenderSystem.disableBlend();
    }

    /** What a mind on its way out adds to the picture: a sick cast over everything, and something tall at the very edge of the eye. */
    private static void madness(GuiGraphics g, int w, int h) {
        int level = Sanity.level();
        if (level >= 3) {
            float pulse = level >= 6 ? 0.75f + 0.25f * (float) Math.sin(System.nanoTime() / 1e9 * 2.4) : 1f;
            g.fill(0, 0, w, h, (int) ((level - 2) * 9 * pulse) << 24 | (level >= 5 ? 0x240404 : 0x1A1608));
        }
        if (Sanity.shadowTicks <= 0) return;
        float a = Mth.clamp(Math.min(Sanity.shadowTicks, 6) / 6f, 0f, 1f) * 0.8f;
        int bw = Math.max(10, w / 26), x0 = Sanity.shadowSide > 0 ? w - bw - 2 : 2, top = h / 5;
        int black = (int) (a * 255) << 24;
        g.fill(x0 + bw / 4, top, x0 + bw - bw / 4, top + bw / 2, black);                                       // head
        g.fill(x0 + bw / 3, top + bw / 2, x0 + bw - bw / 3, h, black);                                         // a body far too long
        g.fill(x0, top + bw / 2 + 2, x0 + bw, top + bw / 2 + 5, black);                                        // shoulders
        g.fill(x0, top + bw / 2 + 2, x0 + 2, top + bw * 4, black);                                             // arms that reach the knees
        g.fill(x0 + bw - 2, top + bw / 2 + 2, x0 + bw, top + bw * 4, black);
        int eye = (int) (a * 230) << 24 | 0xFFECBE;
        g.fill(x0 + bw / 3, top + bw / 5, x0 + bw / 3 + 1, top + bw / 5 + 1, eye);
        g.fill(x0 + bw - bw / 3 - 1, top + bw / 5, x0 + bw - bw / 3, top + bw / 5 + 1, eye);
    }

    /** Six marks in the corner: the more of them are lit, the less of your mind is your own. */
    private static void sanityMarks(GuiGraphics g, Minecraft mc) {
        if (!VerityConfig.SANITY_HUD.get() || ScreenFx.stage < 2 || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        int level = Sanity.level();
        if (level == 0 && Sanity.points < 1f) return;
        int x = 6, y = 6;
        g.drawString(mc.font, "Рассудок", x, y, level >= 5 ? 0xFFB02020 : 0xFFA0A0A0, true);
        int px = x + mc.font.width("Рассудок") + 6;
        int lit = level >= 5 ? 0xFFB02020 : level >= 3 ? 0xFFE08A1C : 0xFFEBCC05;
        for (int i = 0; i < Sanity.MAX_LEVEL; i++) {
            boolean on = i < level && !(level >= 5 && RNG.nextInt(30) == 0);
            int bx = px + i * 8 + (level >= 6 ? RNG.nextInt(3) - 1 : 0);
            g.fill(bx, y, bx + 6, y + 8, 0xFF101010);
            g.fill(bx + 1, y + 1, bx + 5, y + 7, on ? lit : 0xFF3A3A3A);
        }
    }

    /** "Connection lost". It is not: the game goes on underneath, and after a few seconds he owns up. */
    private static void fakeDisconnect(GuiGraphics g, Font font, int w, int h) {
        g.fill(0, 0, w, h, 0xFF0E0C0B);
        if (dev.verity.client.FourthWall.crashTicks > 26) {
            g.drawCenteredString(font, Component.translatable("disconnect.lost"), w / 2, h / 2 - 44, 0xFFAAAAAA);
            g.drawCenteredString(font, "Верити: тебе нельзя уходить.", w / 2, h / 2 - 24, 0xFFFFFFFF);
            int bx = w / 2 - 100, by = h / 2 + 8;                     // a button that is only a picture of a button
            g.fill(bx, by, bx + 200, by + 20, 0xFF000000);
            g.fill(bx + 1, by + 1, bx + 199, by + 19, 0xFF6C6C6C);
            g.drawCenteredString(font, Component.translatable("gui.toTitle"), w / 2, by + 6, 0xFFE0E0E0);
            return;
        }
        for (int i = 0; i < 9; i++) {
            int y = RNG.nextInt(h), bh = 1 + RNG.nextInt(Math.max(2, h / 18));
            g.fill(0, y, w, y + bh, RNG.nextBoolean() ? 0x66EBCC05 : 0x55B02020);
        }
        g.pose().pushPose();
        g.pose().translate(w / 2f + RNG.nextInt(5) - 2, h / 2f - 22 + RNG.nextInt(5) - 2, 0);
        g.pose().scale(4f, 4f, 1f);
        g.drawCenteredString(font, "ШУЧУ", 0, 0, 0xFFEBCC05);
        g.pose().popPose();
    }

    /** His real face, far too close, for half a second. */
    private static void jumpscare(GuiGraphics g, int w, int h) {
        boolean blink = RNG.nextInt(5) == 0;
        g.fill(0, 0, w, h, blink ? 0xFF000000 : 0xFFB9A63E);
        if (blink) return;
        FaceParams p = FaceParams.of(Emotion.TEETH);
        p.mouthW = 0.74f; p.open = 0.95f; p.curve = 0.3f; p.eyeRx = 0.1f; p.eyeRy = 0.13f; p.pupil = 0.12f;
        p.glitch = 0.7f; p.seed = RNG.nextInt();
        scareFace.draw(p);
        int size = (int) (h * (1.9f + RNG.nextFloat() * 0.25f));
        int x = (w - size) / 2 + RNG.nextInt(31) - 15, y = (h - size) / 2 + RNG.nextInt(31) - 15 + h / 12;
        g.blit(scareFace.id, x, y, 0f, 0f, size, size, size, size);
        g.fill(0, 0, w, h, 0x38200000);
    }

    private static void countdown(GuiGraphics g, Font font, int w) {
        if (ScreenFx.daysLeft < 0) return;
        int d = ScreenFx.daysLeft;
        String big = d <= 0 ? "СЕГОДНЯ" : String.valueOf(d);
        String small = d <= 0 ? "ЧТО-ТО ПРИДЁТ" : d == 1 ? "ДЕНЬ ДО ТОГО, КАК ЧТО-ТО ПРИДЁТ" : "ДНЯ ДО ТОГО, КАК ЧТО-ТО ПРИДЁТ";
        boolean flick = RNG.nextInt(40) == 0;
        int jx = flick ? RNG.nextInt(5) - 2 : 0;
        int color = flick ? 0xFFB02020 : 0xFFEBCC05;
        g.pose().pushPose();
        g.pose().translate(w - 8 + jx, 8, 0);
        g.pose().scale(2.5f, 2.5f, 1f);
        g.drawString(font, big, -font.width(big), 0, color, true);
        g.pose().popPose();
        g.drawString(font, small, w - 8 - font.width(small), 8 + 24, 0xFFCFCFCF, true);
    }

    private static String speakerName() {
        if (!subName.isEmpty()) return subName;
        return switch (subProfile) {
            case "box" -> "???";
            case "support" -> "Служба поддержки Verity™";
            case "operator" -> "Оператор";
            case "friend" -> "Twixxel";
            default -> "Верити";
        };
    }

    private static void subtitles(GuiGraphics g, Font font, int w, int h) {
        if (subText.isEmpty() || !VerityConfig.SUBTITLES.get()) return;
        float t = (System.nanoTime() - subStart) / 1e9f;
        float total = subSeconds + 1.6f;
        if (t > total) { subText = ""; return; }
        boolean loud = subProfile.equals("monster") || subProfile.equals("rage");
        String text = Sanity.garble(loud ? subText.toUpperCase(java.util.Locale.ROOT) : subText);
        int shown = Mth.clamp((int) (text.length() * (t / Math.max(subSeconds * 0.92f, 0.3f))) + 1, 0, text.length());
        float alpha = Mth.clamp((total - t) / 0.5f, 0f, 1f);
        int a = (int) (alpha * 255);
        if (a < 8) return;
        List<FormattedCharSequence> lines = font.split(Component.literal(text.substring(0, shown)), Math.min(340, w - 40));
        int lh = 11, bw = 0;
        for (FormattedCharSequence l : lines) bw = Math.max(bw, font.width(l));
        int by = (int) (h * 0.60f) - lines.size() * lh, bx = (w - bw) / 2;      // above the ball when he is held in the hand
        int nameColor = loud ? 0xB02020 : subProfile.equals("box") ? 0xA0A0A0 : 0xEBCC05;
        g.fill(bx - 6, by - 14, bx + bw + 6, by + lines.size() * lh + 3, (int) (alpha * 150) << 24);
        g.drawString(font, speakerName(), bx, by - 11, a << 24 | nameColor, true);
        int y = by;
        for (FormattedCharSequence l : lines) {
            int jx = loud ? RNG.nextInt(3) - 1 : 0, jy = loud ? RNG.nextInt(3) - 1 : 0;
            g.drawString(font, l, bx + jx, y + jy, a << 24 | (loud ? 0xFFD0D0 : 0xFFFFFF), true);
            y += lh;
        }
    }

    private Hud() {}
}
