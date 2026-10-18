package dev.verity.client.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.verity.VerityMod;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * Makes entities speak and tells the renderers how wide to open the mouth.
 * Scripted lines play their baked .ogg; anything else goes through Piper (or babble) on a worker thread.
 */
public final class Voice {
    /** rate: how fast it is played (below 1 when a failing mind hears him lower and slower), so the mouth keeps time. */
    private record Utterance(byte[] envelope, long startNanos, SoundInstance sound, float rate) {}

    public static boolean debugTalk;
    private static final float HOP = 0.02f;
    private static final Map<Integer, Utterance> ACTIVE = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Verity-Voice");
        t.setDaemon(true);
        return t;
    });
    private static Map<String, byte[]> baked;
    private static final Map<String, Float> bakedSeconds = new HashMap<>();

    private static synchronized Map<String, byte[]> baked() {
        if (baked == null) {
            baked = new HashMap<>();
            try (Reader r = new InputStreamReader(Minecraft.getInstance().getResourceManager().open(VerityMod.id("lipsync.json")), StandardCharsets.UTF_8)) {
                JsonObject lines = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("lines");
                for (Map.Entry<String, JsonElement> e : lines.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    baked.put(e.getKey(), Base64.getDecoder().decode(o.get("e").getAsString()));
                    bakedSeconds.put(e.getKey(), o.get("d").getAsFloat());
                }
            } catch (Exception e) {
                VerityMod.LOGGER.error("Cannot read lipsync.json", e);
            }
        }
        return baked;
    }

    /**
     * @param onDuration told how long the line lasts (seconds) as soon as that is known, for the subtitles
     */
    public static void say(Entity entity, String lineId, String text, String profile, String emotion, float volume, Consumer<Float> onDuration) {
        Minecraft mc = Minecraft.getInstance();
        stop(entity.getId());
        float rate = dev.verity.client.fx.Sanity.voicePitch();
        byte[] env = lineId.isEmpty() ? null : baked().get(lineId);
        if (env != null) {
            SoundInstance s = new EntityBoundSoundInstance(SoundEvent.createVariableRangeEvent(VerityMod.id("voice." + lineId)),
                    SoundSource.VOICE, volume, rate, entity, entity.level().random.nextLong());
            mc.getSoundManager().play(s);
            ACTIVE.put(entity.getId(), new Utterance(env, System.nanoTime() + 40_000_000L, s, rate));
            onDuration.accept(bakedSeconds.getOrDefault(lineId, env.length * HOP) / rate);
            return;
        }
        onDuration.accept(Math.max(1.2f, text.length() * 0.075f));           // estimate until the audio exists
        WORKER.execute(() -> {
            float[] y = PiperTts.available() ? PiperTts.speak(clean(text), profile, emotion) : null;
            if (y == null) y = Babble.speak(text, profile);
            float[] audio = y;
            byte[] envelope = VoiceDsp.envelope(audio, VoiceRecipe.sampleRate);
            mc.execute(() -> {
                if (entity.isRemoved()) return;
                stop(entity.getId());
                PcmSound s = new PcmSound(entity, VoiceDsp.toPcm(audio), VoiceRecipe.sampleRate, volume, rate);
                mc.getSoundManager().play(s);
                ACTIVE.put(entity.getId(), new Utterance(envelope, System.nanoTime() + 60_000_000L, s, rate));
                onDuration.accept(audio.length / (float) VoiceRecipe.sampleRate / rate);
            });
        });
    }

    public static void stop(int entityId) {
        Utterance u = ACTIVE.remove(entityId);
        if (u != null) Minecraft.getInstance().getSoundManager().stop(u.sound());
    }

    /** How loudly this entity is speaking right now, 0..1. Drives lip-sync. */
    public static float level(int entityId) {
        if (debugTalk) {
            double t = System.nanoTime() / 1e9;
            return (float) Math.max(0, Math.sin(t * 9.0) * 0.6 + Math.sin(t * 23.0) * 0.4);
        }
        Utterance u = ACTIVE.get(entityId);
        if (u == null) return 0f;
        float t = (System.nanoTime() - u.startNanos()) / 1e9f * u.rate() / HOP;
        if (t < 0f) return 0f;
        int i = (int) t;
        if (i >= u.envelope().length - 1) {
            if (i > u.envelope().length + 25) ACTIVE.remove(entityId);
            return 0f;
        }
        float f = t - i;
        return ((u.envelope()[i] & 0xFF) * (1 - f) + (u.envelope()[i + 1] & 0xFF) * f) / 255f;
    }

    public static boolean speaking(int entityId) {
        Utterance u = ACTIVE.get(entityId);
        return u != null && (System.nanoTime() - u.startNanos()) / 1e9f * u.rate() < u.envelope().length * HOP;
    }

    /** Piper reads plain text: drop emoji, markup and anything else it would stumble over. */
    private static String clean(String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || " .,!?;:-—…'\"()".indexOf(cp) >= 0) sb.appendCodePoint(cp);
            else if (Character.isWhitespace(cp)) sb.append(' ');
        });
        String s = sb.toString().trim();
        return s.length() > 600 ? s.substring(0, 600) : s;
    }

    private Voice() {}
}
