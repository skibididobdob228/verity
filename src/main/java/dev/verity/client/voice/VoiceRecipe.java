package dev.verity.client.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.verity.VerityMod;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** verity_voice.json: the recipe shared with the offline voice baker. */
public final class VoiceRecipe {
    public record Profile(String name, float pitch, float tempo, float drive, float combMs, float combGain, float echoMs, float echoFb,
                          float lowpass, float highpass, float gain, String layer, float layerMix, float ringHz, float ringDepth, int crush, float intonation) {
        public Profile withTempo(float t) {
            return new Profile(name, pitch, t, drive, combMs, combGain, echoMs, echoFb, lowpass, highpass, gain, layer, layerMix, ringHz, ringDepth, crush, intonation);
        }
    }

    /** How an emotion changes the delivery: see "prosody" in verity_voice.json. */
    public record Mood(float tempo, float pitch, float range, float pause, float noise, float noiseW, float wobble) {}

    private static final Mood PLAIN = new Mood(1f, 1f, 0.6f, 1f, 0.667f, 0.8f, 0f);
    private static final Map<String, Mood> MOODS = new HashMap<>();

    public static int sampleRate = 22050;
    public static String model = "ru_RU-dmitri-medium";
    private static final Map<String, Profile> PROFILES = new HashMap<>();
    /** Profiles that speak with another Piper voice than Verity's own (the friend he imitates, the support line). */
    private static final Map<String, String> MODELS = new HashMap<>();

    public static String modelFor(String profile) { return MODELS.getOrDefault(profile, model); }

    static {
        try (Reader r = new InputStreamReader(VoiceRecipe.class.getResourceAsStream("/verity_voice.json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            sampleRate = root.get("sampleRate").getAsInt();
            model = root.get("model").getAsString();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("profiles").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                if (o.has("model")) MODELS.put(e.getKey(), o.get("model").getAsString());
                PROFILES.put(e.getKey(), new Profile(e.getKey(), f(o, "pitch", 1), f(o, "tempo", 1), f(o, "drive", 0), f(o, "combMs", 0),
                        f(o, "combGain", 0), f(o, "echoMs", 0), f(o, "echoFb", 0), f(o, "lowpass", 0), f(o, "highpass", 0), f(o, "gain", 1),
                        o.has("layer") ? o.get("layer").getAsString() : null, f(o, "layerMix", 0), f(o, "ringHz", 0), f(o, "ringDepth", 0), (int) f(o, "crush", 0), f(o, "intonation", 1)));
            }
            if (root.has("prosody")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("prosody").getAsJsonObject("emotions").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    MOODS.put(e.getKey(), new Mood(f(o, "tempo", 1), f(o, "pitch", 1), f(o, "range", 0.6f), f(o, "pause", 1), f(o, "noise", 0.667f), f(o, "noiseW", 0.8f), f(o, "wobble", 0)));
                }
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("Cannot read verity_voice.json", e);
        }
    }

    private static float f(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }

    public static Profile profile(String name) {
        Profile p = PROFILES.get(name);
        return p != null ? p : PROFILES.getOrDefault("ball", new Profile("ball", 1.16f, 1f, 0, 0, 0, 0, 0, 0, 0, 1, null, 0, 0, 0, 0, 1f));
    }

    public static Mood mood(String emotion) {
        Mood m = emotion == null ? null : MOODS.get(emotion.toLowerCase(java.util.Locale.ROOT));
        return m != null ? m : MOODS.getOrDefault("neutral", PLAIN);
    }

    private VoiceRecipe() {}
}
