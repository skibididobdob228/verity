package dev.verity.client.voice;

import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import dev.verity.client.voice.VoiceRecipe.Mood;
import dev.verity.client.voice.VoiceRecipe.Profile;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

/** Runs the Piper text-to-speech program for lines that have no pre-recorded voice (AI answers). */
public final class PiperTts {
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Verity-Piper");
        t.setDaemon(true);
        return t;
    });
    private static Boolean available;
    private static Path exe, model;

    public static synchronized boolean available() {
        if (available == null) {
            Path base = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("verity").resolve("piper");
            String cfgExe = VerityConfig.PIPER_PATH.get(), cfgModel = VerityConfig.PIPER_MODEL.get();
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            exe = (cfgExe.isBlank() ? base.resolve(windows ? "piper.exe" : "piper") : Path.of(cfgExe)).toAbsolutePath();
            model = (cfgModel.isBlank() ? base.resolve("voices").resolve(VoiceRecipe.model + ".onnx") : Path.of(cfgModel)).toAbsolutePath();
            available = VerityConfig.TTS_ENABLED.get() && Files.isRegularFile(exe) && Files.isRegularFile(model);
            VerityMod.LOGGER.info("Verity voice: Piper {} ({}, {})", available ? "found" : "not found, using babble", exe, model);
        }
        return available;
    }

    /** The .onnx a profile speaks with: its own voice if it has one and the file is there, otherwise Verity's. */
    private static Path voiceFor(String profile) {
        String m = VoiceRecipe.modelFor(profile);
        if (m.equals(VoiceRecipe.model)) return model;
        Path own = model.getParent().resolve(m + ".onnx");
        return Files.isRegularFile(own) ? own : model;
    }

    /** Blocking; call from a worker thread. Returns the finished voice or null on failure. */
    @Nullable
    public static float[] speak(String text, String profileName, String emotion) {
        Profile p = VoiceRecipe.profile(profileName);
        Mood mood = VoiceRecipe.mood(emotion);
        // every sentence (and the deeper layer under an angry voice) is its own run of Piper: start them all at once
        java.util.concurrent.Future<float[]> under = p.layer() == null ? null
                : POOL.submit(() -> line(text, VoiceRecipe.profile(p.layer()).withTempo(p.tempo()), mood));
        float[] y = line(text, p, mood);
        if (y == null) return null;
        if (under != null) {
            float[] z = null;
            try { z = under.get(40, TimeUnit.SECONDS); } catch (Exception ignored) { }
            if (z != null) {
                VoiceDsp.finish(y, 1f); VoiceDsp.finish(z, 1f);
                float[] mix = new float[Math.max(y.length, z.length)];
                for (int i = 0; i < mix.length; i++) mix[i] = (i < y.length ? y[i] : 0f) + (i < z.length ? z[i] : 0f) * p.layerMix();
                y = mix;
            }
        }
        return VoiceDsp.finish(y, p.gain());
    }

    /** One whole line in one voice: sentences with their own melody and pauses, then the effect chain. */
    @Nullable
    private static float[] line(String text, Profile p, Mood mood) {
        List<Prosody.Sentence> sentences = Prosody.split(text);
        float pitch = Prosody.pitch(p, mood);
        List<java.util.concurrent.Future<float[]>> takes = new ArrayList<>();
        Path voice = voiceFor(p.name());
        for (Prosody.Sentence s : sentences) takes.add(POOL.submit(() -> one(s.text(), pitch * p.tempo() * mood.tempo() * Prosody.brevity(s.text()), mood, voice)));
        int sr = VoiceRecipe.sampleRate, total = 0;
        List<float[]> parts = new ArrayList<>();
        for (int n = 0; n < sentences.size(); n++) {
            float[] x;
            try { x = takes.get(n).get(40, TimeUnit.SECONDS); } catch (Exception e) { x = null; }
            if (x == null) continue;
            Prosody.Sentence s = sentences.get(n);
            x = Prosody.lengthen(Prosody.trim(x, sr), sr, s.text());
            x = Prosody.intone(x, sr, s.end(), Prosody.isWh(s.text()), mood, p.intonation());
            if (s.end() == '!') for (int k = 0; k < x.length; k++) x[k] *= 1.12f;          // said with force
            float[] bent = VoiceDsp.resample(x, pitch);                                    // the whole voice lower: one steady ratio, so nothing drifts
            parts.add(bent);
            total += bent.length;
            if (n < sentences.size() - 1) {
                float[] gap = new float[(int) (sr * Prosody.pause(s.end()) * mood.pause())];
                parts.add(gap);
                total += gap.length;
            }
        }
        if (total < 64) return null;
        float[] y = new float[total];
        int at = 0;
        for (float[] part : parts) { System.arraycopy(part, 0, y, at, part.length); at += part.length; }
        return VoiceDsp.fx(y, p, sr);
    }

    @Nullable
    private static float[] one(String text, float lengthScale, Mood mood, Path voice) {
        try {
            Process proc = new ProcessBuilder(exe.toString(), "--model", voice.toString(), "--output_raw",
                    "--length_scale", String.format(java.util.Locale.ROOT, "%.4f", lengthScale),
                    "--noise_scale", String.format(java.util.Locale.ROOT, "%.3f", mood.noise()),
                    "--noise_w", String.format(java.util.Locale.ROOT, "%.3f", mood.noiseW()), "--sentence_silence", "0", "-q")
                    .directory(exe.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream in = proc.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
                in.write('\n');
            }
            byte[] raw = proc.getInputStream().readAllBytes();
            if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly();
            if (raw.length < 64) return null;
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] x = new float[raw.length / 2];
            for (int i = 0; i < x.length; i++) x[i] = b.getShort() / 32768f;
            return x;
        } catch (Exception e) {
            VerityMod.LOGGER.warn("Piper failed: {}", e.toString());
            return null;
        }
    }

    private PiperTts() {}
}
