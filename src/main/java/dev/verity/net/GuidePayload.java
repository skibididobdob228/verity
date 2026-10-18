package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** "That way": a place he found for this player, shown as a pointer at the top of the screen for a while. */
public record GuidePayload(int x, int y, int z, String label, int seconds) implements CustomPacketPayload {
    /** y when the height is not known (a village far away, in chunks nobody has generated yet). */
    public static final int NO_Y = -32768;
    public static final Type<GuidePayload> TYPE = new Type<>(VerityMod.id("guide"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GuidePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GuidePayload::x, ByteBufCodecs.VAR_INT, GuidePayload::y, ByteBufCodecs.VAR_INT, GuidePayload::z,
            ByteBufCodecs.STRING_UTF8, GuidePayload::label, ByteBufCodecs.VAR_INT, GuidePayload::seconds, GuidePayload::new);

    @Override
    public Type<GuidePayload> type() { return TYPE; }
}
