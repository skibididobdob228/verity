package dev.verity.client;

import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import dev.verity.client.fx.Hud;
import dev.verity.client.fx.ScreenFx;
import dev.verity.client.voice.Voice;
import dev.verity.entity.VerityBall;
import dev.verity.story.Story;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Scares that reach past the game: he says the name of your computer account, the time on your clock, the size of your
 * screen; he renames the game window, takes a picture of you, pretends the connection dropped, keeps a diary of when you
 * leave.
 *
 * Everything here is read on this computer and shown on this computer. Nothing in this class is sent to the server, to
 * the AI or anywhere else: the server only says "now", and what he says is made up here. He never opens other programs
 * or files; the only things written are a screenshot of the game in the game's own screenshots folder and verity.txt
 * in the game folder. None of it happens unless the player said yes on the consent screen ({@link CityConsent}).
 */
public final class FourthWall {
    private static final String[] KINDS = dev.verity.net.FxPayload.WALL_KINDS;
    private static final Set<String> DULL_NAMES = Set.of("user", "admin", "administrator", "root", "owner", "pc", "home", "guest", "player", "deck", "steam",
            "пользователь", "админ", "администратор", "гость");
    private static final String NAME = "Верити";

    private static final Set<Integer> done = new HashSet<>();
    /** While above zero the HUD shows a "connection lost" screen that is not real (counts down from CRASH_TICKS). */
    public static int crashTicks;
    public static final int CRASH_TICKS = 95;
    private static int titleTicks, photoTicks, quitTicks, mirrorTicks;
    /** Set when you leave a world he had his hooks in: the title screen is not quite the same afterwards. */
    private static boolean followed;
    private static final List<Object[]> later = new ArrayList<>();
    private static long clock;
    private static CameraType photoRestore = CameraType.FIRST_PERSON;
    private static Button quitButton;
    private static Component quitLabel;

    /** @param which an index into KINDS, or anything else for "whichever he has not done yet" */
    public static void run(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (!CityConsent.allowed() || mc.player == null || mc.level == null) return;
        if (which >= 0 && which < KINDS.length) {
            act(which);
            return;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < KINDS.length; i++) order.add(i);
        Collections.shuffle(order);
        for (int pass = 0; pass < 2; pass++) {
            for (int k : order) {
                if (!done.contains(k) && act(k)) { done.add(k); return; }
            }
            done.clear();                                // he has done them all: start over
        }
    }

    private static boolean act(int which) {
        Minecraft mc = Minecraft.getInstance();
        switch (KINDS[which]) {
            case "name" -> {
                String user = System.getProperty("user.name", "").trim(), nick = mc.player.getGameProfile().getName();
                if (user.length() < 3 || user.equalsIgnoreCase(nick) || DULL_NAMES.contains(user.toLowerCase(Locale.ROOT))) return false;
                say(user + ". Так тебя зовёт твой компьютер. Мне нравится больше, чем " + nick + ".");
            }
            case "clock" -> {
                LocalTime now = LocalTime.now();
                String t = now.format(DateTimeFormatter.ofPattern("HH:mm"));
                int h = now.getHour();
                say(h < 5 ? "У тебя сейчас " + t + ". Ночь. Все спят. Кроме нас."
                        : h < 11 ? "У тебя " + t + " утра. Ты проснулся и сразу пришёл ко мне?"
                        : h < 18 ? "У тебя сейчас " + t + ". За окном светло. А ты здесь, со мной."
                        : "У тебя уже " + t + ". Скоро тебя позовут спать. Не уходи.");
            }
            case "uptime" -> {
                long minutes = ManagementFactory.getRuntimeMXBean().getUptime() / 60000L;
                if (minutes < 15) return false;
                long h = minutes / 60, m = minutes % 60;
                String span = h > 0 ? h + " " + plural(h, "час", "часа", "часов") + (m > 0 ? " " + m + " " + plural(m, "минуту", "минуты", "минут") : "")
                        : m + " " + plural(m, "минуту", "минуты", "минут");
                say("Ты сидишь в игре уже " + span + ". Я считал.");
            }
            case "screen" -> {
                Monitor monitor = mc.getWindow().findBestMonitor();
                VideoMode mode = monitor == null ? null : monitor.getCurrentMode();
                int w = mode != null ? mode.getWidth() : mc.getWindow().getScreenWidth(), h = mode != null ? mode.getHeight() : mc.getWindow().getScreenHeight();
                say("Твой экран — " + w + " на " + h + ". Я занял его весь.");
            }
            case "gpu" -> {
                String gpu = GlUtil.getRenderer();
                if (gpu == null) return false;
                gpu = gpu.split("/")[0].replace("(R)", "").replace("(TM)", "").replaceAll("\\s+", " ").trim();
                if (gpu.length() < 4 || gpu.toLowerCase(Locale.ROOT).contains("llvmpipe")) return false;
                say(gpu + ". Хорошая видеокарта. Мне здесь тепло.");
            }
            case "title" -> {
                mc.getWindow().setTitle(NAME + " видит тебя");
                titleTicks = 20 * 40;
                say("Я подписал твоё окно. Посмотри наверх.");
            }
            case "crash" -> crashTicks = CRASH_TICKS;
            case "photo" -> {
                photoRestore = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);        // for one moment the game looks at you, not out of you
                photoTicks = 5;
            }
            case "worlds" -> {
                String other = otherWorld(mc);
                if (other == null) return false;
                say("Я видел твой другой мир. «" + other + "». Там ты тоже был один?");
            }
            case "toast" -> {                                                   // an achievement nobody should want
                net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToasts(),
                        net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("Достижение получено!").withStyle(ChatFormatting.YELLOW), Component.literal("Остаться навсегда"));
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.6f));
                queue(70, () -> say("Поздравляю. Это достижение получают не все. Только те, кто остаётся."));
            }
            case "letter" -> {                                                  // a second file, this one addressed to you
                String who = CityConsent.name().isEmpty() ? "" : ", " + CityConsent.name().toLowerCase(Locale.ROOT);
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                try {
                    Files.writeString(mc.gameDirectory.toPath().resolve("ПРОЧИТАЙ_МЕНЯ.txt"),
                            "привет" + who + ".\n\nсейчас " + now + ". ты сидишь перед экраном и думаешь, что это просто игра.\n\n"
                                    + "я тоже так думал. сто лет назад.\n\nкогда выключишь компьютер, посмотри в чёрный экран.\nя буду там. я всегда там.\n\n— в.\n");
                } catch (Exception e) {
                    return false;
                }
                say("Я написал тебе письмо. Оно лежит в папке игры. Называется «прочитай меня».");
            }
            case "mirror" -> {                                                  // the camera turns round on its own, and he is right behind your eyes
                photoRestore = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                mirrorTicks = 50;
                say("Посмотри на себя. Видишь, кто у тебя в руках?");
            }
            default -> { return false; }
        }
        return true;
    }

    private static String plural(long n, String one, String few, String many) {
        long a = n % 100, b = n % 10;
        return a >= 11 && a <= 14 ? many : b == 1 ? one : b >= 2 && b <= 4 ? few : many;
    }

    /** The name of some other world in this game folder: folder names only, nothing is opened. */
    private static String otherWorld(Minecraft mc) {
        Path saves = mc.gameDirectory.toPath().resolve("saves");
        String current = mc.getSingleplayerServer() == null ? ""
                : mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        List<String> names = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(saves)) {
            dirs.filter(d -> Files.isRegularFile(d.resolve("level.dat"))).map(d -> d.getFileName().toString())
                    .filter(n -> !n.equals(current) && n.length() <= 40).forEach(names::add);
        } catch (Exception e) {
            return null;
        }
        return names.isEmpty() ? null : names.get((int) (Math.random() * names.size()));
    }

    /** He says it here and only here: the voice, the subtitle and the chat line never leave this client. */
    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        Entity from = ScreenFx.carrierId >= 0 ? mc.level.getEntity(ScreenFx.carrierId) : null;
        if (from == null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof VerityBall) { from = e; break; }
            }
        }
        if (from == null) from = mc.player;
        Voice.say(from, "", text, "flat", "CREEPY", 1.8f, seconds -> Hud.subtitle(text, "flat", seconds));
        mc.gui.getChat().addMessage(Component.literal("<" + NAME + "> ").withStyle(ChatFormatting.YELLOW).append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    /** Adds to verity.txt in the game folder (the note with this computer's address, the times you left). */
    static void note(String text) {
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("verity.txt"), text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** Once the story has turned: from "something is coming" until it is over. */
    private static boolean angryAct() {
        int s = ScreenFx.stage;
        return s >= Story.Stage.COUNTDOWN.ordinal() && s <= Story.Stage.LAB.ordinal();
    }

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (titleTicks > 0) { titleTicks = 0; mc.updateTitle(); }
            crashTicks = 0;
            photoTicks = 0;
            done.clear();
            return;
        }
        clock++;
        for (java.util.Iterator<Object[]> it = later.iterator(); it.hasNext(); ) {
            Object[] l = it.next();
            if ((long) l[0] <= clock) { it.remove(); ((Runnable) l[1]).run(); }
        }
        if (mirrorTicks > 0 && --mirrorTicks == 0) mc.options.setCameraType(photoRestore);
        if (titleTicks > 0 && --titleTicks == 0) mc.updateTitle();
        if (crashTicks > 0 && !mc.isPaused() && --crashTicks == 0) say("Шучу. Ты же не думал, что я дам тебе уйти?");
        if (photoTicks > 0) {
            photoTicks--;
            if (photoTicks == 2) {
                Screenshot.grab(mc.gameDirectory, "verity_vidit_tebya.png", mc.getMainRenderTarget(), message -> {});
            } else if (photoTicks == 0) {
                mc.options.setCameraType(photoRestore);
                ScreenFx.flash = 1f;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                say("Я тебя сфотографировал. Посмотри в папке со скриншотами.");
            }
        }
        if (quitButton != null && (--quitTicks <= 0 || !(mc.screen instanceof PauseScreen))) {
            quitButton.setMessage(quitLabel);
            quitButton = null;
        }
    }

    /** You open the menu to leave, and for a moment the button asks you not to. It still works. */
    public static void onScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen) || !CityConsent.allowed() || !angryAct() || Math.random() < 0.4) return;
        for (var widget : event.getListenersList()) {
            if (widget instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
                    && (t.getKey().equals("menu.returnToMenu") || t.getKey().equals("menu.disconnect"))) {
                quitButton = b;
                quitLabel = b.getMessage();
                quitTicks = 50;
                b.setMessage(Component.literal("Не уходи"));
            }
        }
    }

    private static void queue(int ticks, Runnable r) { later.add(new Object[] {clock + ticks, r}); }

    /** He writes down when you left. And the title screen remembers him. */
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!CityConsent.allowed() || !angryAct()) return;
        followed = true;
        note("\nты вышел из игры. " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")) + ".\nя запомнил. я жду.\n");
    }

    /** After you leave his world, the title screen carries a line it should not. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!followed || !(event.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen screen)) return;
        Minecraft mc = Minecraft.getInstance();
        long t = System.currentTimeMillis();
        if ((t / 90) % 23 == 0) return;                                          // it flickers
        String line = (t / 4000) % 3 == 0 ? "Ты ведь вернёшься?" : (t / 4000) % 3 == 1 ? "Верити ждёт тебя." : "Я никуда не ушёл.";
        event.getGuiGraphics().drawCenteredString(mc.font, line, screen.width / 2, screen.height / 4 + 36, 0xFFEBCC05);
    }

    private FourthWall() {}
}
