package com.namo.pvsound.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server settings edited in the /pvsound setting screen.
 */
public record SettingsData(
        String cipherUrl,
        String cipherPassword,
        String searchPrefix,
        int cableLength,
        int extensionLength,
        int maxExtensions,
        int maxSpeakers,
        int speakerRange,
        int subwooferRange,
        int coneAngle,
        int bitrateKbps,
        String activeCipher
) {

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(cipherUrl, 512);
        buf.writeUtf(cipherPassword, 512);
        buf.writeUtf(searchPrefix, 32);
        buf.writeVarInt(cableLength);
        buf.writeVarInt(extensionLength);
        buf.writeVarInt(maxExtensions);
        buf.writeVarInt(maxSpeakers);
        buf.writeVarInt(speakerRange);
        buf.writeVarInt(subwooferRange);
        buf.writeVarInt(coneAngle);
        buf.writeVarInt(bitrateKbps);
        buf.writeUtf(activeCipher, 512);
    }

    public static SettingsData read(FriendlyByteBuf buf) {
        return new SettingsData(
                buf.readUtf(512), buf.readUtf(512), buf.readUtf(32),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(512));
    }
}
