package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** What the player asked out loud, already turned into text on their own computer. No sound is ever sent. */
public record VoiceAskPayload(String text) implements CustomPacketPayload {
    public static final Type<VoiceAskPayload> TYPE = new Type<>(VerityMod.id("voice_ask"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VoiceAskPayload> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(256), VoiceAskPayload::text, VoiceAskPayload::new);

    @Override
    public Type<VoiceAskPayload> type() { return TYPE; }
}
