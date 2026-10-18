package dev.verity.client.selftest;

import dev.verity.VerityMod;
import dev.verity.client.voice.Voice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Development harness, inert unless the JVM is started with -Dverity.selftest=&lt;script&gt;. It creates a throwaway
 * world and plays a script: one step per line, "wait N" (ticks), "cmd ..." (a command without the slash),
 * "shot name", "voice file.wav", "gui on|off", "talk on|off", "volume x", "musicvol x", "camera first|back|front", "inv", "close", "hold", "throw", "use", "pause", "face monster|ball", "consent show|yes [name]",
 * "lan port" (open the world to a second test client), "waitplayers N", "quit".
 * With -Dverity.selftest.world=join:port the client joins such a LAN world instead of creating its own.
 */
public final class SelfTest {
    private static final String SCRIPT = System.getProperty("verity.selftest");
    private static final String WORLD = "verity_selftest";
    private static final Deque<String> steps = new ArrayDeque<>();
    private static boolean started;
    private static int wait = 60, needPlayers;

    public static void init() {
        if (SCRIPT == null) return;
        try {
            List<String> lines = Files.readAllLines(Path.of(SCRIPT));
            for (String l : lines) {
                if (!l.isBlank() && !l.startsWith("#")) steps.add(l.trim());
            }
        } catch (IOException e) {
            VerityMod.LOGGER.error("selftest: cannot read {}", SCRIPT, e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(SelfTest::onScreen);
        NeoForge.EVENT_BUS.addListener(SelfTest::onTick);
        VerityMod.LOGGER.info("selftest: {} steps from {}", steps.size(), SCRIPT);
    }

    private static void onScreen(ScreenEvent.Init.Post event) {
        if (started || !(event.getScreen() instanceof TitleScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        String mode = System.getProperty("verity.selftest.world", "flat");
        if (mode.startsWith("join:")) {                 // second player: join the first one's world over LAN
            String address = "localhost:" + mode.substring(5);
            mc.execute(() -> net.minecraft.client.gui.screens.ConnectScreen.startConnecting(event.getScreen(), mc,
                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                    new net.minecraft.client.multiplayer.ServerData("lab", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null));
            return;
        }
        mc.execute(() -> {
            try {
                Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
                if (Files.exists(dir)) {
                    try (var walk = Files.walk(dir)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                    }
                }
                boolean flat = !"normal".equals(System.getProperty("verity.selftest.world"));
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(Long.getLong("verity.selftest.seed", 20260530L), !flat, false),
                        ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(flat ? WorldPresets.FLAT : WorldPresets.NORMAL).value().createWorldDimensions(),
                        event.getScreen());
            } catch (Exception e) {
                VerityMod.LOGGER.error("selftest: world creation failed", e);
                mc.stop();
            }
        });
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!started || mc.player == null || mc.level == null) return;
        if (wait > 0) { wait--; return; }
        if (needPlayers > 0) {                      // "waitplayers N": hold the script until that many players are in the world
            if (mc.level.players().size() < needPlayers) return;
            needPlayers = 0;
        }
        while (wait == 0 && needPlayers == 0 && !steps.isEmpty()) {
            String step = steps.poll();
            String[] parts = step.split(" ", 2);
            String arg = parts.length > 1 ? parts[1] : "";
            VerityMod.LOGGER.info("selftest: {}", step);
            switch (parts[0]) {
                case "wait" -> wait = Integer.parseInt(arg);
                case "waitplayers" -> needPlayers = Integer.parseInt(arg);
                case "cmd" -> mc.player.connection.sendCommand(arg);
                case "chat" -> mc.player.connection.sendChat(arg);
                case "voice" -> dev.verity.client.voice.VoiceInput.inject(java.nio.file.Path.of(arg));   // a recording instead of the microphone
                case "shot" -> Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), msg -> {});
                case "gui" -> mc.options.hideGui = arg.equals("off");
                case "talk" -> Voice.debugTalk = arg.equals("on");
                case "volume" -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(Double.parseDouble(arg));
                case "musicvol" -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(Double.parseDouble(arg));
                case "lan" -> {                             // open the world to a second (offline, test) player
                    mc.getSingleplayerServer().setUsesAuthentication(false);
                    mc.getSingleplayerServer().publishServer(GameType.CREATIVE, true, Integer.parseInt(arg));
                }
                case "camera" -> mc.options.setCameraType(arg.equals("front") ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT
                        : arg.equals("back") ? net.minecraft.client.CameraType.THIRD_PERSON_BACK : net.minecraft.client.CameraType.FIRST_PERSON);
                case "inv" -> mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                case "close" -> mc.setScreen(null);
                case "pause" -> mc.pauseGame(false);
                case "use_entity" -> {                      // right-click the entity under the crosshair
                    if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && mc.gameMode != null) {
                        mc.gameMode.interact(mc.player, hit.getEntity(), net.minecraft.world.InteractionHand.MAIN_HAND);
                    }
                }
                case "face" -> {                            // turn to the nearest monster or ball this client can see (real or imagined)
                    net.minecraft.world.entity.Entity best = null;
                    for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                        boolean wanted = arg.equals("ball") ? e instanceof dev.verity.entity.VerityBall : arg.equals("player") ? e instanceof net.minecraft.client.player.RemotePlayer : e instanceof dev.verity.entity.VerityMonster;
                        if (wanted && (best == null || e.distanceTo(mc.player) < best.distanceTo(mc.player))) best = e;
                    }
                    if (best != null) {
                        mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, best.position().add(0, arg.equals("ball") ? 0.25 : arg.equals("player") ? 1.6 : 3.0, 0));
                    } else {
                        VerityMod.LOGGER.info("selftest: nothing to face");
                    }
                }
                case "consent" -> dev.verity.client.CityConsent.debug(arg);
                case "hold" -> {                            // put Verity in the main hand, if he is in the hotbar
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getItem(i).getItem() instanceof dev.verity.item.VerityItem) mc.player.getInventory().selected = i;
                    }
                }
                case "slot" -> mc.player.getInventory().selected = Integer.parseInt(arg);
                case "throw" -> mc.player.drop(true);
                case "use" -> {                             // right-click the block under the crosshair
                    if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && mc.gameMode != null) {
                        mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                    }
                }
                case "quit" -> { mc.stop(); return; }
                default -> VerityMod.LOGGER.warn("selftest: unknown step {}", step);
            }
        }
        if (steps.isEmpty() && wait == 0 && needPlayers == 0) mc.stop();
    }

    private SelfTest() {}
}
