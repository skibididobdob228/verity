package dev.verity.client.voice;

import dev.verity.client.voice.VoiceRecipe.Profile;

/** Voice of last resort: one short blip per letter, so he can still "talk" (and move his mouth) without a TTS engine. */
public final class Babble {
    public static float[] speak(String text, String profileName) {
        Profile p = VoiceRecipe.profile(profileName);
        int sr = VoiceRecipe.sampleRate;
        boolean deep = p.pitch() < 0.9f;
        float base = deep ? 120f : 330f * p.pitch();
        int letter = (int) (sr * 0.062f * p.tempo()), gap = (int) (sr * 0.05f), pause = (int) (sr * 0.17f);
        int total = 0;
        for (char c : text.toCharArray()) total += Character.isLetterOrDigit(c) ? letter : c == ' ' ? gap : pause;
        float[] y = new float[total + sr / 10];
        int at = 0;
        double phase = 0;
        for (char c : text.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                at += c == ' ' ? gap : pause;
                continue;
            }
            int h = Character.toLowerCase(c) * 0x9E3779B1;
            float freq = base * (1f + ((h >>> 24) % 9 - 4) * 0.06f);
            boolean vowel = "аеёиоуыэюяaeiouy".indexOf(Character.toLowerCase(c)) >= 0;
            for (int i = 0; i < letter; i++) {
                float t = i / (float) letter;
                float env = (float) (Math.sin(Math.PI * t) * (vowel ? 1.0 : 0.6));
                phase += 2 * Math.PI * freq * (1f + 0.15f * t) / sr;
                float s = (float) (Math.sin(phase) + 0.45 * Math.sin(phase * 2) + (deep ? 0.5 * Math.signum(Math.sin(phase * 0.5)) : 0.2 * Math.sin(phase * 3)));
                y[at + i] = s * env * 0.5f;
            }
            at += letter;
        }
        if (p.drive() > 0) {
            for (int i = 0; i < y.length; i++) y[i] = (float) Math.tanh(y[i] * p.drive());
        }
        return VoiceDsp.finish(y, p.gain() * 0.8f);
    }

    private Babble() {}
}
