package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Slow-changing story state the client needs for the HUD, the atmosphere and the face of the Verity item (who carries him, how he looks).
 * sanity is 0..100 (the level is a sixth of it) and is only ever non-zero for his own person.
 */
public record StatePayload(int stage, int daysLeft, float darkness, float dread, boolean music, int emotion, float corruption, int carrierId, float sanity)
        implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE = new Type<>(VerityMod.id("state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.of((buf, p) -> {
        buf.writeVarInt(p.stage); buf.writeVarInt(p.daysLeft + 1); buf.writeFloat(p.darkness); buf.writeFloat(p.dread); buf.writeBoolean(p.music);
        buf.writeVarInt(p.emotion); buf.writeFloat(p.corruption); buf.writeVarInt(p.carrierId + 1); buf.writeFloat(p.sanity);
    }, buf -> new StatePayload(buf.readVarInt(), buf.readVarInt() - 1, buf.readFloat(), buf.readFloat(), buf.readBoolean(),
            buf.readVarInt(), buf.readFloat(), buf.readVarInt() - 1, buf.readFloat()));

    @Override
    public Type<StatePayload> type() { return TYPE; }
}
