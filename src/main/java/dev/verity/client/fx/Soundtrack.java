package dev.verity.client.fx;

import dev.verity.VerityConfig;
import dev.verity.VerityMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/**
 * The music of a world with Verity in it. Minecraft's own music is switched off there and an old ballroom tune plays
 * instead, in six states of decay that follow the player's sanity: whole at first, a stuck and distant record in the
 * middle, at the end only a low breath of what it was.
 *
 * The six tracks that come with the mod are made from the mod's own tune (pipeline/build_music.py). A player who owns
 * music they would rather hear (the idea is The Caretaker's "Everywhere at the End of Time", which also has six stages)
 * can put their own .ogg files into &lt;game folder&gt;/verity/music/: {@link MusicStages} reads the stage off each file's
 * name ("stage 3", or a record-side code such as "A1 - ..."), and files with no stage in their name play at any level.
 * The mod itself ships none of that music.
 */
public final class Soundtrack {
    private static final String[] BUILT_IN = {"decay_a", "decay_b", "decay_c", "decay_d", "decay_e", "decay_f"};
    private static final SoundEvent FROM_FILE = SoundEvent.createVariableRangeEvent(VerityMod.id("voice.dynamic"));
    private static final Random RNG = new Random();

    private static Track current;
    private static int currentStage, gap = 60, rescan;
    /** The player's own files: [0] = any stage, [1..6] = that stage. */
    @SuppressWarnings("unchecked")
    private static final List<Path>[] own = new List[7];
    private static Path last;

    private static boolean active(Minecraft mc) {
        return mc.level != null && mc.player != null && VerityConfig.REPLACE_MUSIC.get();
    }

    /** No vanilla music while ours is in charge. (The title screen keeps its own.) */
    public static void onSelectMusic(SelectMusicEvent event) {
        if (active(Minecraft.getInstance())) event.overrideMusic(null);
    }

    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active(mc)) {
            if (current != null) { mc.getSoundManager().stop(current); current = null; }
            gap = 60;
            return;
        }
        if (mc.isPaused()) return;
        int stage = Mth.clamp(Sanity.level(), 1, 6);
        boolean hunted = ScreenFx.huntTune();                      // the night he comes has its own record
        if (current != null) {
            if (!mc.getSoundManager().isActive(current)) {
                current = null;
                gap = 50 + RNG.nextInt(90);
            } else if (hunted || stage != currentStage) {
                current.fadeOut();                                  // the mind moved on: so does the record
            }
        }
        if (current == null && !hunted && --gap <= 0) {
            if (--rescan <= 0) { scan(mc); rescan = 6; }
            Path file = pick(stage);
            current = new Track(file == null ? SoundEvent.createVariableRangeEvent(VerityMod.id("music." + BUILT_IN[stage - 1])) : FROM_FILE, file);
            currentStage = stage;
            last = file;
            mc.getSoundManager().play(current);
        }
    }

    /** Looks through verity/music for .ogg files and sorts them by the stage named in their path. */
    private static void scan(Minecraft mc) {
        for (int i = 0; i < own.length; i++) own[i] = new ArrayList<>();
        Path dir = mc.gameDirectory.toPath().resolve("verity").resolve("music");
        if (!Files.isDirectory(dir)) return;
        int other = 0;
        try (Stream<Path> files = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String rel = dir.relativize(p).toString().toLowerCase(Locale.ROOT).replace('\\', '/');
                if (!rel.endsWith(".ogg")) {
                    if (rel.matches(".*\\.(mp3|flac|wav|m4a|opus|wma|aac)$")) other++;
                    continue;
                }
                own[MusicStages.of(rel)].add(p);
            }
        } catch (IOException e) {
            VerityMod.LOGGER.warn("Verity music: cannot read {}: {}", dir, e.toString());
        }
        if (other > 0) VerityMod.LOGGER.info("Verity music: {} file(s) in {} are not .ogg and are skipped (import-music.sh converts them)", other, dir);
    }

    /** One of the player's own files for this stage, or null to play the built-in track. */
    @Nullable
    private static Path pick(int stage) {
        List<Path> pool = own[stage] == null ? List.of() : own[stage];
        if (pool.isEmpty() && own[0] != null) pool = own[0];
        if (pool.isEmpty()) return null;
        Path choice = pool.get(RNG.nextInt(pool.size()));
        if (choice.equals(last) && pool.size() > 1) choice = pool.get((pool.indexOf(choice) + 1) % pool.size());
        return choice;
    }

    private static final class Track extends AbstractTickableSoundInstance {
        @Nullable private final Path file;
        private float fade;
        private boolean leaving;

        Track(SoundEvent event, @Nullable Path file) {
            super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.file = file;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.volume = 0.001f;
        }

        void fadeOut() { leaving = true; }

        @Override
        public void tick() {
            fade = Mth.clamp(fade + (leaving ? -0.0125f : 0.02f), 0f, 1f);
            volume = Math.max(0.001f, fade * VerityConfig.MUSIC_VOLUME.get().floatValue());
            if (leaving && fade <= 0f) stop();
        }

        @Override
        public boolean canStartSilent() { return true; }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            if (file == null) return soundBuffers.getStream(sound.getPath(), looping);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return new JOrbisAudioStream(Files.newInputStream(file));
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, Util.nonCriticalIoPool());
        }
    }

    private Soundtrack() {}
}
