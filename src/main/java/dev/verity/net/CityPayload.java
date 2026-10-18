package dev.verity.net;

import dev.verity.VerityMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, only after the player said yes on the consent screen: the city their time zone is named after, and which
 * of a short fixed list of well-known programs ("telegram", "discord", ...) are running, comma separated. Program names only:
 * the client never looks at windows, files or anything inside those programs.
 * And the first name the player typed in on that screen themselves, if they did (empty otherwise).
 * The server keeps all of it in memory only. City and programs are never given to the AI or to other players; the name is given
 * to the AI (that is the point of typing it), and lines with it are spoken to that player alone.
 */
public record CityPayload(String city, String apps, String name) implements CustomPacketPayload {
    public static final Type<CityPayload> TYPE = new Type<>(VerityMod.id("city"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.stringUtf8(48), CityPayload::city, ByteBufCodecs.stringUtf8(96), CityPayload::apps,
                    ByteBufCodecs.stringUtf8(24), CityPayload::name, CityPayload::new);

    /** A first name and nothing else: letters, a space or a hyphen, at most 20 characters. Anything that is not a name comes back empty. */
    public static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^\\p{L} '-]", "").replaceAll("\\s+", " ");
        if (s.length() > 20) s = s.substring(0, 20);
        s = s.replaceAll("^[ '-]+|[ '-]+$", "");
        if (s.codePoints().filter(Character::isLetter).count() < 2) return "";
        return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
    }

    @Override
    public Type<CityPayload> type() { return TYPE; }
}
