package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Somebody (the ball, the monster, the box) says something. {@code lineId} is a scripted line with a baked
 * voice file, or empty for free text (AI answers), which the client voices itself.
 */
public record SayPayload(int entityId, String lineId, String text, int emotion, String profile) implements CustomPacketPayload {
    public static final Type<SayPayload> TYPE = new Type<>(VerityMod.id("say"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SayPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SayPayload::entityId, ByteBufCodecs.STRING_UTF8, SayPayload::lineId, ByteBufCodecs.STRING_UTF8, SayPayload::text,
            ByteBufCodecs.VAR_INT, SayPayload::emotion, ByteBufCodecs.STRING_UTF8, SayPayload::profile, SayPayload::new);

    @Override
    public Type<SayPayload> type() { return TYPE; }
}
