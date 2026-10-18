package dev.verity.client.fx;

import dev.verity.client.voice.VoiceInput;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** While V is held: a microphone and your own voice as a moving meter. Then what he heard. */
public final class MicHud {
    private static final int YELLOW = 0xFFEBCC05, RED = 0xFFD03030, GRAY = 0xFF9A9A9A;

    public static void render(GuiGraphics g, Font font, int w, int h) {
        VoiceInput.State st = VoiceInput.state();
        if (st == VoiceInput.State.IDLE) return;
        long now = System.currentTimeMillis();
        float[] levels = VoiceInput.levels();
        String text = switch (st) {
            case LISTENING -> "Говори… отпусти V, когда закончишь   " + (now - VoiceInput.startedAt()) / 1000 + " с";
            case THINKING -> "Верити слушает" + ".".repeat((int) (now / 350 % 4));
            case DONE -> "«" + VoiceInput.message() + "»";
            case ERROR -> VoiceInput.message();
            default -> "";
        };
        int color = st == VoiceInput.State.ERROR ? RED : st == VoiceInput.State.LISTENING ? YELLOW : GRAY;
        int meterW = levels.length * 3, textW = Math.min(font.width(text), w / 2);
        int boxW = 26 + Math.max(meterW, textW) + 8, boxH = 34;
        int x0 = 6, y0 = h / 2 - boxH / 2;             // the left edge, clear of the chat, the hotbar and him in your hand
        g.fill(x0, y0, x0 + boxW, y0 + boxH, 0x90000000);
        g.fill(x0, y0 + boxH - 1, x0 + boxW, y0 + boxH, color);

        // the microphone: a capsule on a stand, pulsing with your voice
        int mx = x0 + 6, my = y0 + 5;
        int pulse = st == VoiceInput.State.LISTENING ? (int) (VoiceInput.level() * 3) : 0;
        if (pulse > 0) g.fill(mx - pulse, my - pulse, mx + 10 + pulse, my + 14 + pulse, (color & 0x00FFFFFF) | 0x30000000);
        g.fill(mx + 2, my, mx + 8, my + 13, color);                 // body
        g.fill(mx + 1, my + 1, mx + 9, my + 12, color);
        g.fill(mx + 3, my + 2, mx + 7, my + 3, 0xFF202020);         // grille
        g.fill(mx + 3, my + 5, mx + 7, my + 6, 0xFF202020);
        g.fill(mx + 3, my + 8, mx + 7, my + 9, 0xFF202020);
        g.fill(mx - 1, my + 9, mx, my + 16, color);                 // the cradle
        g.fill(mx + 10, my + 9, mx + 11, my + 16, color);
        g.fill(mx - 1, my + 16, mx + 11, my + 17, color);
        g.fill(mx + 4, my + 17, mx + 6, my + 21, color);            // stem and foot
        g.fill(mx + 1, my + 21, mx + 9, my + 22, color);
        if (st == VoiceInput.State.ERROR) {                         // struck through
            for (int i = 0; i < 20; i++) g.fill(mx - 2 + i * 14 / 20, my + i, mx + i * 14 / 20, my + i + 1, RED);
        }

        // the meter: your loudness over the last seconds, newest on the right
        int bx = x0 + 22, base = y0 + 16;
        for (int i = 0; i < levels.length; i++) {
            float v = st == VoiceInput.State.THINKING ? 0.15f + 0.1f * (float) Math.sin(now / 160.0 + i * 0.5) : levels[i];
            int bh = Math.max(1, (int) (v * 12));
            int c = st == VoiceInput.State.LISTENING ? (v > 0.95f ? RED : YELLOW) : GRAY;
            g.fill(bx + i * 3, base - bh, bx + i * 3 + 2, base + bh, c);
        }
        String shown = font.width(text) > textW ? font.plainSubstrByWidth(text, textW - 6) + "…" : text;
        g.drawString(font, shown, x0 + 22, y0 + boxH - 11, st == VoiceInput.State.ERROR ? 0xFFFF8080 : 0xFFFFFFFF, false);
    }

    private MicHud() {}
}
