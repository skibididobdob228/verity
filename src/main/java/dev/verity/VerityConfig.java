package dev.verity;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class VerityConfig {
    public static final ModConfigSpec COMMON, CLIENT;

    // --- common (server side of the story and the AI)
    public static final ModConfigSpec.ConfigValue<String> AI_PROVIDER, AI_BASE_URL, AI_MODEL, AI_API_KEY;
    public static final ModConfigSpec.IntValue AI_MAX_TOKENS, AI_TIMEOUT_SECONDS;
    public static final ModConfigSpec.DoubleValue AI_TEMPERATURE;
    public static final ModConfigSpec.BooleanValue STORY_AUTOSTART, MONSTER_BREAKS_BLOCKS, MONSTER_KILLS, CHAT_NEEDS_NAME, SANITY;
    public static final ModConfigSpec.DoubleValue DAY_SCALE;
    public static final ModConfigSpec.IntValue HEAR_RANGE;

    // --- client
    public static final ModConfigSpec.BooleanValue TTS_ENABLED, SUBTITLES, SANITY_HUD, REPLACE_MUSIC, VOICE_INPUT;
    public static final ModConfigSpec.ConfigValue<String> PIPER_PATH, PIPER_MODEL, CITY_SCARE, REAL_NAME, WHISPER_PATH, WHISPER_MODEL, WHISPER_LANGUAGE;
    public static final ModConfigSpec.DoubleValue FX_INTENSITY, NIGHT_DARKNESS, MUSIC_VOLUME;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Verity's brain. He answers chat messages through a language model.").push("ai");
        AI_PROVIDER = b.comment("ollama = local Ollama server (free, offline); openai = any OpenAI-compatible chat API (Groq, OpenRouter, LM Studio...);",
                "off = scripted answers only.").define("provider", "ollama");
        AI_BASE_URL = b.comment("ollama: http://127.0.0.1:11434   openai: the URL that ends before /chat/completions, e.g. https://api.groq.com/openai/v1")
                .define("baseUrl", "http://127.0.0.1:11434");
        AI_MODEL = b.comment("The model name as the server knows it, e.g. gemma3:12b for Ollama.").define("model", "gemma3:12b");
        AI_API_KEY = b.comment("API key for the openai provider. Leave empty to use the VERITY_API_KEY environment variable.").define("apiKey", "");
        AI_MAX_TOKENS = b.defineInRange("maxTokens", 220, 32, 2000);
        AI_TEMPERATURE = b.comment("Lower = steadier, more sensible answers; higher = wilder. 0.6 suits gemma3:12b.").defineInRange("temperature", 0.6, 0.0, 2.0);
        AI_TIMEOUT_SECONDS = b.defineInRange("timeoutSeconds", 25, 3, 120);
        CHAT_NEEDS_NAME = b.comment("true: he only answers messages that mention him (\"Верити, ...\"). false: he answers everything said near him.")
                .define("chatNeedsName", false);
        HEAR_RANGE = b.comment("How close (blocks) you must be for him to hear your chat.").defineInRange("hearRange", 24, 4, 128);
        b.pop();
        b.comment("The story.").push("story");
        STORY_AUTOSTART = b.comment("Deliver the parcel on its own shortly after a player first joins a world. If false, start with /verity start.")
                .define("autoStart", true);
        DAY_SCALE = b.comment("Story speed: 1.0 = one story day per Minecraft day, 0.5 = twice as fast.").defineInRange("dayScale", 1.0, 0.05, 10.0);
        MONSTER_BREAKS_BLOCKS = b.comment("The monster smashes doors, glass and other weak blocks that stand between it and you.").define("monsterBreaksBlocks", true);
        SANITY = b.comment("Sanity: darkness and everything he does wear the player's mind down through six levels, each with worse hallucinations;",
                "light, sleep and food bring it back. false turns the whole thing off.").define("sanity", true);
        MONSTER_KILLS = b.comment("true: being caught kills you. false: you black out and wake up at home.").define("monsterKills", false);
        b.pop();
        COMMON = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.comment("Voice for AI answers (scripted story lines are pre-recorded and always play).",
                "Uses Piper (https://github.com/rhasspy/piper): put the 'piper' folder into <game dir>/verity/ or set the paths below.",
                "Without Piper he babbles in beeps instead of words (the mouth still moves).").push("voice");
        TTS_ENABLED = c.define("ttsEnabled", true);
        PIPER_PATH = c.comment("Path to the piper executable. Empty = <game dir>/verity/piper/piper").define("piperPath", "");
        PIPER_MODEL = c.comment("Path to the .onnx voice. Empty = <game dir>/verity/piper/voices/<model from the voice recipe>.onnx").define("piperModel", "");
        c.pop();
        c.comment("Asking him out loud: hold the talk key (V by default, see Controls). The microphone is only open while the key is held;",
                "speech is turned into text on this computer by whisper.cpp (put whisper-cli and a ggml model into <game dir>/verity/whisper/)",
                "and only the text is sent, as if typed. Nothing is recorded or kept.").push("voiceInput");
        VOICE_INPUT = c.define("enabled", true);
        WHISPER_PATH = c.comment("Path to whisper-cli. Empty = <game dir>/verity/whisper/whisper-cli").define("whisperPath", "");
        WHISPER_MODEL = c.comment("Path to the ggml model. Empty = the biggest .bin in <game dir>/verity/whisper/models/").define("whisperModel", "");
        WHISPER_LANGUAGE = c.comment("Language you speak: ru, en, uk... or auto.").define("language", "ru");
        c.pop();
        c.push("display");
        SUBTITLES = c.comment("Show what he says as a subtitle above the hotbar (it is always written to chat).").define("subtitles", true);
        SANITY_HUD = c.comment("Show the six sanity marks in the corner of the screen.").define("sanityHud", true);
        FX_INTENSITY = c.comment("Strength of screen shake, static and flashes. 0 turns them off.").defineInRange("fxIntensity", 1.0, 0.0, 1.5);
        CITY_SCARE = c.comment("When very angry, Verity may show that he knows things about your real computer (your city by time zone, your account name,",
                "the time, running programs by name...) and, if you typed it in, call you by your real name. A screen asks about all of it at once.",
                "ask = ask every time you enter a world, always = allow without asking, never = never.").define("cityScare", "ask");
        REAL_NAME = c.comment("The first name you last typed on that screen, so that you need not type it again. Empty = he does not know it.",
                "It is only ever sent together with a \"yes\" on that screen.").define("realName", "");
        NIGHT_DARKNESS = c.comment("How dark the nights are. 1 = pitch black beyond a dozen blocks, 0 = vanilla nights.",
                "Carrying a torch or lantern in either hand lets you see a little further.").defineInRange("nightDarkness", 1.0, 0.0, 1.0);
        c.pop();
        c.comment("Music. In a world, Minecraft's own music is replaced by an old tune in six states of decay that follow your sanity.",
                "Your own .ogg files in <game dir>/verity/music/ are played instead of the built-in tracks: a file or folder with \"stage 3\" in its name",
                "plays at sanity level 3, files with no stage in their name at any level.").push("music");
        REPLACE_MUSIC = c.comment("false: leave Minecraft's music alone.").define("replaceMusic", true);
        MUSIC_VOLUME = c.comment("Volume of this music, on top of Minecraft's Music slider.").defineInRange("musicVolume", 0.8, 0.0, 1.0);
        c.pop();
        CLIENT = c.build();
    }

    private VerityConfig() {}
}
