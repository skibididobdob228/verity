package dev.verity.client.voice;

import com.mojang.blaze3d.platform.InputConstants;
import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import dev.verity.net.VoiceAskPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Push-to-talk: hold V and ask him out loud. The microphone is opened only while the key is held, the sound never
 * leaves the computer: it is written to a temporary file, turned into text by a local whisper.cpp (verity/whisper/),
 * deleted, and only the text goes to the server, exactly as if it had been typed.
 */
public final class VoiceInput {
    public static final KeyMapping TALK = new KeyMapping("key.verity.talk", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.verity");

    public enum State { IDLE, LISTENING, THINKING, DONE, ERROR }

    private static final int RATE = 16000, MAX_SECONDS = 20;
    private static final float[] LEVELS = new float[48];          // the last 2.4 s of loudness, for the meter
    private static volatile State state = State.IDLE;
    private static volatile String message = "";
    private static volatile long stateAt, startedAt;
    private static volatile float level;
    private static int levelHead;
    @Nullable private static volatile Thread recorder;
    private static volatile boolean stopRequested;
    @Nullable private static volatile byte[] injected;            // selftest: a recording to use instead of the microphone

    public static State state() { return state; }
    public static String message() { return message; }
    public static long stateAt() { return stateAt; }
    public static long startedAt() { return startedAt; }
    public static float level() { return level; }

    /** Loudness history, oldest first. */
    public static float[] levels() {
        synchronized (LEVELS) {
            float[] out = new float[LEVELS.length];
            for (int i = 0; i < out.length; i++) out[i] = LEVELS[(levelHead + i) % LEVELS.length];
            return out;
        }
    }

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!VerityConfig.VOICE_INPUT.get()) return;
        boolean down = TALK.isDown() && mc.screen == null && mc.player != null;
        if (down && state != State.LISTENING && state != State.THINKING) start(null);
        else if (!down && state == State.LISTENING && injected == null) stopRequested = true;
        if ((state == State.DONE || state == State.ERROR) && System.currentTimeMillis() - stateAt > 4500) set(State.IDLE, "");
    }

    /** Selftest: plays a WAV file (16 kHz mono) through the same pipe as the microphone, meter and all. */
    public static void inject(Path wav) {
        try {
            byte[] all = Files.readAllBytes(wav);
            int data = 44;
            for (int i = 12; i + 8 < all.length; ) {                 // find the "data" chunk instead of trusting the header size
                String id = new String(all, i, 4, StandardCharsets.US_ASCII);
                int size = ByteBuffer.wrap(all, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (id.equals("data")) { data = i + 8; break; }
                i += 8 + size;
            }
            byte[] pcm = java.util.Arrays.copyOfRange(all, data, all.length);
            start(pcm);
        } catch (IOException e) {
            set(State.ERROR, "Не могу прочитать " + wav.getFileName());
        }
    }

    private static void start(@Nullable byte[] fake) {
        if (state == State.LISTENING || state == State.THINKING) return;
        injected = fake;
        stopRequested = false;
        startedAt = System.currentTimeMillis();
        synchronized (LEVELS) { java.util.Arrays.fill(LEVELS, 0f); }
        set(State.LISTENING, "");
        Thread t = new Thread(VoiceInput::record, "Verity-Mic");
        t.setDaemon(true);
        recorder = t;
        t.start();
    }

    private static void record() {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buf = new byte[RATE / 20 * 2];                       // 50 ms
        byte[] fake = injected;
        try {
            if (fake != null) {
                for (int at = 0; at < fake.length && !stopRequested; at += buf.length) {
                    int n = Math.min(buf.length, fake.length - at);
                    System.arraycopy(fake, at, buf, 0, n);
                    pcm.write(buf, 0, n);
                    meter(buf, n);
                    Thread.sleep(50);
                }
            } else {
                AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, fmt));
                line.open(fmt, buf.length * 4);
                line.start();
                try {
                    while (!stopRequested && pcm.size() < RATE * 2 * MAX_SECONDS) {
                        int n = line.read(buf, 0, buf.length);
                        if (n <= 0) continue;
                        pcm.write(buf, 0, n);
                        meter(buf, n);
                    }
                } finally {
                    line.stop();
                    line.close();
                }
            }
        } catch (Exception e) {
            VerityMod.LOGGER.warn("Verity voice input: microphone failed: {}", e.toString());
            set(State.ERROR, "Микрофон недоступен");
            injected = null;
            return;
        }
        injected = null;
        level = 0f;
        byte[] audio = pcm.toByteArray();
        float peak = 0f;
        for (int i = 0; i + 1 < audio.length; i += 2) peak = Math.max(peak, Math.abs((short) ((audio[i] & 0xFF) | audio[i + 1] << 8)) / 32768f);
        if (audio.length < RATE * 2 * 3 / 10) { set(State.ERROR, "Слишком коротко. Держи V, пока говоришь"); return; }
        if (peak < 0.02f) { set(State.ERROR, "Ничего не слышно. Проверь микрофон"); return; }
        set(State.THINKING, "");
        String text = transcribe(audio);
        if (text == null) return;
        if (text.isBlank()) { set(State.ERROR, "Не разобрал. Попробуй ещё раз"); return; }
        set(State.DONE, text);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().getConnection() != null) PacketDistributor.sendToServer(new VoiceAskPayload(text));
        });
    }

    private static void meter(byte[] buf, int n) {
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            float s = (short) ((buf[i] & 0xFF) | buf[i + 1] << 8) / 32768f;
            sum += s * s;
        }
        float rms = (float) Math.sqrt(sum / Math.max(1, n / 2));
        float v = (float) Math.min(1.0, Math.max(0.0, (20 * Math.log10(rms + 1e-6) + 55) / 45));    // -55..-10 dBFS -> 0..1
        level = v;
        synchronized (LEVELS) { LEVELS[levelHead] = v; levelHead = (levelHead + 1) % LEVELS.length; }
    }

    // ------------------------------------------------------------------ whisper.cpp
    private static Path base() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize().resolve("verity").resolve("whisper");
    }

    @Nullable
    private static Path exe() {
        String cfg = VerityConfig.WHISPER_PATH.get();
        if (!cfg.isBlank()) return Files.isRegularFile(Path.of(cfg)) ? Path.of(cfg) : null;
        for (String n : new String[] {"whisper-cli", "whisper-cli.exe", "main", "main.exe"}) {
            Path p = base().resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    @Nullable
    private static Path model() {
        String cfg = VerityConfig.WHISPER_MODEL.get();
        if (!cfg.isBlank()) return Files.isRegularFile(Path.of(cfg)) ? Path.of(cfg) : null;
        try (var files = Files.list(base().resolve("models"))) {
            // the biggest model there is the best one there
            return files.filter(f -> f.getFileName().toString().endsWith(".bin")).max(java.util.Comparator.comparingLong(f -> f.toFile().length())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Words whisper hears often enough to be worth a nudge: his name and the game's own vocabulary. */
    private static final String PROMPT = "Верити, Майнкрафт. Где деревня? Как скрафтить верстак? Алмазы, крипер, эндермен, Нижний мир, Край, незерит.";

    @Nullable
    private static String transcribe(byte[] pcm) {
        Path exe = exe(), model = model();
        if (exe == null || model == null) {
            set(State.ERROR, "Нет распознавания речи: положи whisper в verity/whisper");
            return null;
        }
        Path wav = null;
        try {
            Files.createDirectories(base());
            wav = Files.createTempFile(base(), "ask", ".wav");
            try (OutputStream out = Files.newOutputStream(wav)) { out.write(header(pcm.length)); out.write(pcm); }
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-m", model.toString(), "-f", wav.toString(), "-l", VerityConfig.WHISPER_LANGUAGE.get(),
                    "-nt", "-np", "-t", Integer.toString(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2))), "--prompt", PROMPT));
            Process proc = new ProcessBuilder(cmd).directory(exe.getParent().toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = proc.getInputStream().readAllBytes();
            if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroyForcibly(); set(State.ERROR, "Распознавание зависло"); return null; }
            return tidy(new String(out, StandardCharsets.UTF_8));
        } catch (Exception e) {
            VerityMod.LOGGER.warn("Verity voice input: whisper failed: {}", e.toString());
            set(State.ERROR, "Распознавание не запустилось");
            return null;
        } finally {
            if (wav != null) try { Files.deleteIfExists(wav); } catch (IOException ignored) { }   // the recording is not kept
        }
    }

    /** One line of text; whisper's guesses at silence ("[музыка]", "Продолжение следует...") dropped; his name spelled right. */
    static String tidy(String raw) {
        String s = raw.replace('\n', ' ').replaceAll("\\[[^]]*]|\\([^)]*\\)", " ").replaceAll("\\s{2,}", " ").trim();
        String low = s.toLowerCase(java.util.Locale.ROOT);
        if (low.matches("(продолжение следует|субтитры.*|спасибо за просмотр|редактор субтитров.*)[.!…]*")) return "";
        s = s.replaceAll("(?iuU)\\b(верит[еи]|вериди|верети|вирити|вэрити|ферити)\\b", "Верити");
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private static byte[] header(int dataLen) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
        return b.array();
    }

    private static void set(State s, String msg) {
        state = s;
        message = msg;
        stateAt = System.currentTimeMillis();
    }

    private VoiceInput() {}
}
