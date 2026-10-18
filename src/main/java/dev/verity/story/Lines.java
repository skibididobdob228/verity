package dev.verity.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.verity.VerityMod;
import dev.verity.entity.Emotion;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nullable;

/** The scripted lines from verity_lines.json. */
public final class Lines {
    public record Line(String id, String text, Emotion emotion, String profile) {}

    private static final Map<String, Line> LINES = new HashMap<>();
    private static final Map<String, List<String>> POOLS = new HashMap<>();
    private static final Random RNG = new Random();

    static {
        try (Reader r = new InputStreamReader(Lines.class.getResourceAsStream("/verity_lines.json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lines").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                LINES.put(e.getKey(), new Line(e.getKey(), o.get("text").getAsString(),
                        Emotion.byName(o.get("emotion").getAsString(), Emotion.HAPPY), o.get("profile").getAsString()));
            }
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pools").entrySet()) {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : (JsonArray) e.getValue()) ids.add(id.getAsString());
                POOLS.put(e.getKey(), ids);
            }
        } catch (Exception e) {
            VerityMod.LOGGER.error("Cannot read verity_lines.json", e);
        }
    }

    @Nullable
    public static Line get(String id) {
        return LINES.get(id);
    }

    /** A random line id from a named pool. */
    public static String pick(String pool) {
        List<String> ids = POOLS.get(pool);
        return ids == null || ids.isEmpty() ? pool : ids.get(RNG.nextInt(ids.size()));
    }

    private Lines() {}
}
