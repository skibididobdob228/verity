package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A one-shot screen effect for one player. */
public record FxPayload(int kind, float amount, int ticks) implements CustomPacketPayload {
    public static final int SHAKE = 0, STATIC = 1, FLASH = 2, JUMPSCARE = 3, BLACKOUT = 4, GLITCH = 5, NOTE = 6, WALL = 7, SEE = 8;
    /** For WALL, 'ticks' is an index into this list (anything else: the client's choice). What each one says is decided on the client. */
    /** For SEE (development: start one hallucination now), 'ticks' is an index into this list. */
    public static final String[] SEE_KINDS = {"sound", "shadow", "balls", "figure", "charge", "chat", "double"};
    public static final String[] WALL_KINDS = {"name", "clock", "uptime", "screen", "gpu", "title", "crash", "photo", "worlds", "toast", "letter", "mirror"};

    public static final Type<FxPayload> TYPE = new Type<>(VerityMod.id("fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FxPayload::kind, ByteBufCodecs.FLOAT, FxPayload::amount, ByteBufCodecs.VAR_INT, FxPayload::ticks, FxPayload::new);

    @Override
    public Type<FxPayload> type() { return TYPE; }
}
