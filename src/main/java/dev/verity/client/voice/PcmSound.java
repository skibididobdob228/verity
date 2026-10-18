package dev.verity.client.voice;

import dev.verity.VerityMod;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/** Plays freshly synthesised speech (raw PCM) from an entity's position. */
public class PcmSound extends AbstractTickableSoundInstance {
    private static final SoundEvent PLACEHOLDER = SoundEvent.createVariableRangeEvent(VerityMod.id("voice.dynamic"));

    private final Entity entity;
    private final short[] pcm;
    private final int sampleRate;

    public PcmSound(Entity entity, short[] pcm, int sampleRate, float volume, float pitch) {
        super(PLACEHOLDER, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.entity = entity; this.pcm = pcm; this.sampleRate = sampleRate; this.volume = volume; this.pitch = pitch;
        x = entity.getX(); y = entity.getY(); z = entity.getZ();
    }

    @Override
    public void tick() {
        if (entity.isRemoved()) {
            stop();
        } else {
            x = entity.getX(); y = entity.getY(); z = entity.getZ();
        }
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream());
    }

    private final class Stream implements AudioStream {
        private final AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        private int at;

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int size) {
            int n = Math.min(size / 2, pcm.length - at);
            if (n <= 0) return null;
            ByteBuffer b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder());
            b.asShortBuffer().put(pcm, at, n);
            at += n;
            b.limit(n * 2);
            return b;
        }

        @Override
        public void close() {}
    }
}
