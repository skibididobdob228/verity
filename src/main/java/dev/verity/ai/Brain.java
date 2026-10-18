package dev.verity.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import dev.verity.entity.Emotion;
import dev.verity.story.Lines;
import dev.verity.story.Story;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Verity's answers to free-form chat. Talks to any OpenAI-compatible chat endpoint (Ollama's /v1 by default),
 * off the server thread; the reply comes back as a small JSON object: what to say, which face to make, and
 * optionally one harmless thing to do. Without a working model he falls back to scripted lines.
 */
public final class Brain {
    private record Reply(ServerPlayer player, String say, Emotion emotion, String action, String item, int count) {
        Reply withSay(String s) { return new Reply(player, s, emotion, action, item, count); }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ConcurrentLinkedQueue<Runnable> done = new ConcurrentLinkedQueue<>();
    private volatile boolean busy, summarizing;
    /** Turns kept word for word; older ones are folded into {@link Story#summary}. */
    private static final int KEEP_TURNS = 16, SUMMARIZE_AT = 26, FOLD = 12;
    private int failures;
    /** Size of the last prompt as the server counted it; shown by /verity context. */
    public volatile int lastPromptTokens;
    private long lastGiftTick = -100000;
    private long tick;

    /** Runs finished requests' results on the server thread. */
    public void tick(Story story) {
        tick++;
        Runnable r;
        while ((r = done.poll()) != null) r.run();
    }

    private final java.util.ArrayDeque<Object[]> waiting = new java.util.ArrayDeque<>();

    public static boolean enabled() {
        String p = VerityConfig.AI_PROVIDER.get().toLowerCase(Locale.ROOT);
        return !p.equals("off") && !p.isBlank();
    }

    /** Loads the model into memory ahead of the first question so that answer does not take half a minute. */
    public void warmUp() {
        if (!enabled()) return;
        Thread t = new Thread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", VerityConfig.AI_MODEL.get());
                body.addProperty("max_tokens", 1);
                JsonArray msgs = new JsonArray();
                msgs.add(message("user", "ок"));
                body.add("messages", msgs);
                send(body, 90);
            } catch (Exception ignored) {
            }
        }, "Verity-AI-warmup");
        t.setDaemon(true);
        t.start();
    }

    public void ask(Story story, ServerPlayer player, String text, String answeredQuestion) {
        Entity speaker = story.speaker();
        if (speaker == null) return;
        if (!enabled() || failures >= 3 && tick % 2400 > 200) {
            story.later(25, () -> scripted(story, player, text, answeredQuestion));
            return;
        }
        if (busy) {                         // several people talking at once: answer them in turn rather than drop anyone
            if (waiting.size() < 4) waiting.add(new Object[] {player, text, answeredQuestion});
            return;
        }
        busy = true;
        Locator.Result found = Locator.lookup(story, player, text);
        if (!found.isEmpty()) VerityMod.LOGGER.info("Verity locator for '{}': {}", text, found.found().stream().map(Locator.Found::fact).toList());
        boolean slogan = Persona.sloganTurn(story);
        String system = Persona.systemPrompt(story, player, text, answeredQuestion, found, slogan);
        JsonObject body = new JsonObject();
        body.addProperty("model", VerityConfig.AI_MODEL.get());
        body.addProperty("max_tokens", VerityConfig.AI_MAX_TOKENS.get());
        body.addProperty("temperature", VerityConfig.AI_TEMPERATURE.get());
        JsonObject fmt = new JsonObject();
        fmt.addProperty("type", "json_object");
        body.add("response_format", fmt);
        JsonArray msgs = new JsonArray();
        msgs.add(message("system", system));
        int from = Math.max(0, story.memory.size() - KEEP_TURNS);
        while (from < story.memory.size() - 1 && !story.memory.get(from)[0].equals("user")) from++;
        for (int i = from; i < story.memory.size(); i++) {
            String[] m = story.memory.get(i);
            // earlier assistant turns are shown in the reply format so the model keeps answering in it
            msgs.add(message(m[0], m[0].equals("assistant") ? "{\"say\": " + quote(m[1]) + "}" : m[1]));
        }
        body.add("messages", msgs);

        Thread t = new Thread(() -> {
            Reply reply;
            try {
                String content = send(body, VerityConfig.AI_TIMEOUT_SECONDS.get());
                reply = withCoords(slogan ? parse(player, content) : withoutSlogan(parse(player, content)), found);
                failures = 0;
            } catch (Exception e) {
                failures++;
                VerityMod.LOGGER.warn("Verity AI request failed ({}): {}", failures, e.toString());
                reply = null;
            }
            Reply result = reply;
            done.add(() -> {
                busy = false;
                Object[] next = waiting.poll();
                if (next != null) story.later(40, () -> { if (((ServerPlayer) next[0]).isAlive()) ask(story, (ServerPlayer) next[0], (String) next[1], (String) next[2]); });
                if (result == null || result.say().isBlank()) {
                    if (failures == 1) story.say(story.speaker(), "ai_error");
                    else scripted(story, player, text, answeredQuestion);
                    return;
                }
                deliver(story, result);
                Locator.Found target = found.target();
                if (target != null) story.guide(player, target);
                if (story.memory.size() >= SUMMARIZE_AT) summarize(story);
            });
        }, "Verity-AI");
        t.setDaemon(true);
        t.start();
    }

    private void deliver(Story story, Reply r) {
        Entity speaker = story.speaker();
        if (speaker == null) return;
        boolean monster = speaker instanceof dev.verity.entity.VerityMonster;
        String profile = monster ? "monster" : switch (r.emotion()) {
            case ANGRY -> "rage";
            case NEUTRAL, SAD, CREEPY, BLANK, CRYING, SCARED, SLEEPY -> "flat";
            default -> "ball";
        };
        // a furious Verity in the friendly acts would break the story's pacing: keep rage for when he has earned it
        if (profile.equals("rage") && story.stage().ordinal() < Story.Stage.COUNTDOWN.ordinal()) profile = "flat";
        story.sayText(speaker, r.say(), r.emotion(), profile);
        switch (r.action()) {
            case "give" -> give(story, r);
            case "door" -> story.openNearbyDoor(r.player(), true);
            case "rain" -> story.later(100, () -> story.overworld().setWeatherParameters(0, 6000, true, false));   // "in five seconds"
            case "music" -> story.tune(320);
            case "enchant" -> enchant(story, r);
            case "clear" -> story.overworld().setWeatherParameters(12000, 0, false, false);
            default -> {}
        }
    }

    /**
     * Long-term memory. When the conversation outgrows what is sent word for word, the oldest turns are handed to the model
     * to boil down into a few lines, which then ride along in every prompt ("what you remember from earlier talks").
     */
    public void summarize(Story story) {
        if (summarizing || !enabled() || story.memory.size() <= FOLD) return;
        summarizing = true;
        java.util.List<String[]> old = new java.util.ArrayList<>(story.memory.subList(0, FOLD));
        StringBuilder talk = new StringBuilder();
        for (String[] m : old) talk.append(m[0].equals("assistant") ? "Верити: " : "Игрок: ").append(m[1]).append('\n');
        JsonObject body = new JsonObject();
        body.addProperty("model", VerityConfig.AI_MODEL.get());
        body.addProperty("max_tokens", 260);
        body.addProperty("temperature", 0.2);
        JsonArray msgs = new JsonArray();
        msgs.add(message("system", "Ты ведёшь память персонажа по имени Верити из игры Minecraft. Тебе дают прежние заметки и кусок разговора Верити с игроком. "
                + "Перепиши заметки, добавив новое: что игрок рассказал о себе, о чём просил, что обещал, как относится к Верити, что Верити обещал или отказался делать. "
                + "Не больше 8 коротких строк, каждая начинается с «- ». Только факты из текста, без выдумок и без вступлений. "
                + "Верити мужского рода: пиши о нём «он». Игрока называй «игрок» или по имени, если он его назвал."));
        msgs.add(message("user", "Прежние заметки:\n" + (story.summary.isBlank() ? "(пока нет)" : story.summary) + "\n\nРазговор:\n" + talk));
        body.add("messages", msgs);
        Thread t = new Thread(() -> {
            String result = null;
            try {
                result = send(body, 60).trim();
            } catch (Exception e) {
                VerityMod.LOGGER.warn("Verity AI summary failed: {}", e.toString());
            }
            String text = result;
            done.add(() -> {
                summarizing = false;
                if (text == null || text.isBlank()) return;
                story.summary = text.length() > 1400 ? text.substring(0, 1400) : text;
                // drop exactly the turns that were folded in, if they are still the oldest ones
                for (String[] m : old) {
                    if (!story.memory.isEmpty() && story.memory.get(0) == m) story.memory.remove(0);
                }
                story.setDirty();
                VerityMod.LOGGER.info("Verity AI: folded {} turns into long-term memory ({} chars)", old.size(), story.summary.length());
            });
        }, "Verity-AI-memory");
        t.setDaemon(true);
        t.start();
    }

    private long lastBookTick = -100000;

    /** "I can give you enchantments myself." One book a day, for ten levels, and nothing silly. */
    private void enchant(Story story, Reply r) {
        if (story.stage() == Story.Stage.COUNTDOWN || story.stage() == Story.Stage.HUNT || tick - lastBookTick < 24000 || r.player().experienceLevel < 10) return;
        var reg = story.server().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        ResourceLocation id = ResourceLocation.tryParse(r.item() == null ? "" : r.item().contains(":") ? r.item() : "minecraft:" + r.item());
        if (id == null || !java.util.Set.of("sharpness", "mending", "efficiency", "unbreaking", "protection", "fortune", "looting", "silk_touch", "feather_falling", "power",
                "respiration", "smite", "knockback", "fire_aspect", "infinity", "aqua_affinity", "depth_strider").contains(id.getPath())) return;
        var holder = reg.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, id)).orElse(null);
        if (holder == null) return;
        int level = Math.max(1, Math.min(r.count(), holder.value().getMaxLevel()));
        lastBookTick = tick;
        r.player().giveExperienceLevels(-10);
        ItemStack book = net.minecraft.world.item.EnchantedBookItem.createForEnchantment(new net.minecraft.world.item.enchantment.EnchantmentInstance(holder, level));
        story.note("ты дал игроку зачарованную книгу (" + book.getHoverName().getString() + ", " + id.getPath() + " " + level + ") за 10 уровней, чтобы ему не нужно было искать жителей");
        if (!r.player().getInventory().add(book)) r.player().drop(book, false);
    }

    /** He may hand over small comforts, not solve the game. */
    private void give(Story story, Reply r) {
        if (story.stage() == Story.Stage.COUNTDOWN || story.stage() == Story.Stage.HUNT || tick - lastGiftTick < 1200) return;
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(r.item() == null ? "" : r.item())).orElse(null);
        java.util.Set<Item> allowed = java.util.Set.of(Items.BREAD, Items.COOKED_BEEF, Items.APPLE, Items.GOLDEN_CARROT, Items.TORCH, Items.COOKIE,
                Items.BAKED_POTATO, Items.COOKED_CHICKEN, Items.SWEET_BERRIES, Items.COAL, Items.STICK, Items.OAK_PLANKS, Items.IRON_INGOT,
                Items.EXPERIENCE_BOTTLE, Items.BOOK, Items.MAP);
        if (item == null || !allowed.contains(item)) return;
        lastGiftTick = tick;
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(r.count(), item == Items.IRON_INGOT || item == Items.EXPERIENCE_BOTTLE ? 3 : 8)));
        story.note("ты дал игроку " + r.player().getGameProfile().getName() + ": " + stack.getHoverName().getString() + " ×" + stack.getCount());
        if (!r.player().getInventory().add(stack)) r.player().drop(stack, false);
    }

    /** No model available: a few honest facts by keyword, otherwise one of the stock evasions. */
    private void scripted(Story story, ServerPlayer p, String text, String answeredQuestion) {
        Entity speaker = story.speaker();
        if (speaker == null || story.speaking()) return;
        if (!answeredQuestion.isEmpty()) { story.say(speaker, "q_thanks"); return; }
        Locator.Result found = Locator.lookup(story, p, text);
        if (!found.isEmpty()) {
            String fact = found.found().get(0).fact();
            story.sayText(speaker, fact.length() > 320 ? fact.substring(0, fact.lastIndexOf('.', 320) + 1) : fact, Emotion.HAPPY, "ball");
            if (found.target() != null) story.guide(p, found.target());
            return;
        }
        String fact = WorldFacts.direct(story, p, text.toLowerCase(Locale.ROOT));
        if (fact != null) story.sayText(speaker, fact, Emotion.HAPPY, "ball");
        else story.say(speaker, Lines.pick("fallback"));
    }

    // ------------------------------------------------------------------ HTTP
    private String send(JsonObject body, int timeoutSeconds) throws Exception {
        String base = VerityConfig.AI_BASE_URL.get().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (VerityConfig.AI_PROVIDER.get().equalsIgnoreCase("ollama") && !base.endsWith("/v1")) base += "/v1";
        String key = VerityConfig.AI_API_KEY.get().isBlank() ? System.getenv("VERITY_API_KEY") : VerityConfig.AI_API_KEY.get();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (key != null && !key.isBlank()) req.header("Authorization", "Bearer " + key);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(200, res.body().length())));
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject u = root.getAsJsonObject("usage");
            VerityMod.LOGGER.debug("Verity AI: prompt {} tokens, answer {} tokens", u.has("prompt_tokens") ? u.get("prompt_tokens") : "?",
                    u.has("completion_tokens") ? u.get("completion_tokens") : "?");
            lastPromptTokens = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : 0;
        }
        return root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
    }

    private static Reply parse(ServerPlayer player, String content) {
        String s = content.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return new Reply(player, tidy(s), Emotion.HAPPY, "none", "", 0);       // the model ignored the format: take the text
        JsonObject o = JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
        String say = o.has("say") && !o.get("say").isJsonNull() ? o.get("say").getAsString() : "";
        Emotion emotion = Emotion.byName(o.has("emotion") && o.get("emotion").isJsonPrimitive() ? o.get("emotion").getAsString() : "", Emotion.HAPPY);
        if (emotion == Emotion.TEETH) emotion = Emotion.CREEPY;
        String action = o.has("action") && o.get("action").isJsonPrimitive() ? o.get("action").getAsString().toLowerCase(Locale.ROOT) : "none";
        String item = o.has("item") && o.get("item").isJsonPrimitive() ? o.get("item").getAsString() : "";
        int count = 1;
        try { if (o.has("count")) count = o.get("count").getAsInt(); } catch (Exception ignored) { }
        return new Reply(player, tidy(say), emotion, action, item, count);
    }

    /** Models copy themselves: once the slogan is in the history it would close every line. Outside its turn it is cut. */
    private static final java.util.regex.Pattern SLOGAN = java.util.regex.Pattern.compile(
            "\\s*(Верити\\s*[—–-]\\s*(твой |ваш |ваш личный |твой личный |личный )?(фирменный )?(друг-помощник|помощник)[.!]?(\\s*Знает вс[её][.!]?)?|[Вв]ерити\\s+знает\\s+вс[её]™?[.!]?|(?<=[.!?]\\s)Знает вс[её]™?[.!]?)(?=\\s|$)",
            java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    private static Reply withoutSlogan(Reply r) {
        String s = SLOGAN.matcher(r.say()).replaceAll("").trim();
        return s.length() < 4 ? r : r.withSay(s);
    }

    /** Small models garble numbers: if the answer lost the coordinates he looked up, they are put back word for word. */
    private static Reply withCoords(Reply r, Locator.Result found) {
        Locator.Found target = found.target();
        if (target == null || r.say().isBlank()) return r;
        String x = Integer.toString(target.pos().getX()), z = Integer.toString(target.pos().getZ());
        if (r.say().contains(x) && r.say().contains(z)) return r;
        String say = r.say().replaceAll("\\s*$", "");
        return r.withSay(say + (say.endsWith(".") || say.endsWith("!") || say.endsWith("?") ? " " : ". ") + "Точно: " + target.coords() + ".");
    }

    /** One chat line, no markup, no emoji, not too long. */
    private static String tidy(String say) {
        StringBuilder sb = new StringBuilder();
        say.replace('\n', ' ').replace("*", "").replace("_", "").replace("#", "").codePoints().forEach(cp -> {
            if (cp < 0x2000 || cp >= 0x2010 && cp <= 0x2027) sb.appendCodePoint(cp);          // drops emoji and other pictographs
        });
        String s = sb.toString().trim().replaceAll("\\s{2,}", " ");
        if (s.length() > 320) {
            int cut = Math.max(s.lastIndexOf('.', 320), Math.max(s.lastIndexOf('!', 320), s.lastIndexOf('?', 320)));
            s = cut > 60 ? s.substring(0, cut + 1) : s.substring(0, 320) + "…";
        }
        return s;
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }
}
