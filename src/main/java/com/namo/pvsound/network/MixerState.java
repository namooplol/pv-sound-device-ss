package com.namo.pvsound.network;

import com.namo.pvsound.audio.MixerParam;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a mixer shown in the mixer screen.
 */
public record MixerState(
        float[] params,
        int loopMode,
        boolean playing,
        boolean paused,
        String title,
        String author,
        long positionMs,
        long durationMs,
        List<String> queue,
        int queueSize,
        float levelLeft,
        float levelRight,
        float levelSub,
        String status,
        int[] channelCounts
) {

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(params.length);
        for (float p : params) buf.writeFloat(p);
        buf.writeVarInt(loopMode);
        buf.writeBoolean(playing);
        buf.writeBoolean(paused);
        buf.writeUtf(title, 256);
        buf.writeUtf(author, 256);
        buf.writeVarLong(positionMs);
        buf.writeVarLong(durationMs);
        buf.writeVarInt(queue.size());
        for (String q : queue) buf.writeUtf(q, 256);
        buf.writeVarInt(queueSize);
        buf.writeFloat(levelLeft);
        buf.writeFloat(levelRight);
        buf.writeFloat(levelSub);
        buf.writeUtf(status, 512);
        buf.writeVarIntArray(channelCounts);
    }

    public static MixerState read(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 64);
        float[] params = MixerParam.defaults();
        for (int i = 0; i < count; i++) {
            float value = buf.readFloat();
            MixerParam param = MixerParam.byId(i);
            if (param != null) params[i] = param.clamp(value);
        }
        int loop = buf.readVarInt();
        boolean playing = buf.readBoolean();
        boolean paused = buf.readBoolean();
        String title = buf.readUtf(256);
        String author = buf.readUtf(256);
        long position = buf.readVarLong();
        long duration = buf.readVarLong();
        int queueCount = Math.min(buf.readVarInt(), 32);
        List<String> queue = new ArrayList<>(queueCount);
        for (int i = 0; i < queueCount; i++) queue.add(buf.readUtf(256));
        int queueSize = buf.readVarInt();
        float l = buf.readFloat();
        float r = buf.readFloat();
        float s = buf.readFloat();
        String status = buf.readUtf(512);
        int[] channels = buf.readVarIntArray(8);
        return new MixerState(params, loop, playing, paused, title, author, position, duration,
                queue, queueSize, l, r, s, status, channels);
    }

    public static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) : text;
    }
}
