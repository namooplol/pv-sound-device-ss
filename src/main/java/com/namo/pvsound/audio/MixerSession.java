package com.namo.pvsound.audio;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.SoundAddon;
import com.namo.pvsound.SoundConfig;
import com.namo.pvsound.audio.dsp.MixerDsp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.slib.api.server.world.McServerWorld;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.source.ServerStaticSource;
import su.plo.voice.api.server.config.ServerConfig;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import su.plo.voice.proto.data.audio.codec.opus.OpusEncoderInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusMode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Audio side of one mixer block: a LavaPlayer player, a track queue, the DSP strip and one
 * mono Plasmo Voice static source per connected speaker.
 */
public final class MixerSession {

    public enum LoopMode { OFF, TRACK, QUEUE }

    public record SpeakerSpec(SpeakerChannel channel, Direction facing, boolean subwoofer) {
    }

    private static final class SpeakerOutput {
        final ServerStaticSource source;
        final boolean subwoofer;
        volatile SpeakerChannel channel;
        volatile short distance;

        SpeakerOutput(ServerStaticSource source, SpeakerChannel channel, boolean subwoofer, short distance) {
            this.source = source;
            this.channel = channel;
            this.subwoofer = subwoofer;
            this.distance = distance;
        }
    }

    private final GlobalPos key;
    private final AudioPlayer player;
    private final MixerDsp dsp = new MixerDsp();
    private final Deque<AudioTrack> queue = new ArrayDeque<>();
    private final Map<BlockPos, SpeakerOutput> speakers = new ConcurrentHashMap<>();
    private final Map<SpeakerChannel, AudioEncoder> encoders = new EnumMap<>(SpeakerChannel.class);
    private final Map<UUID, MicBuffer> microphones = new ConcurrentHashMap<>();
    private volatile long micHoldUntil;
    private long streamHoldUntil;

    /** Small jitter buffer per live microphone: voice packets arrive in bursts, the audio thread ticks steadily. */
    private static final class MicBuffer {
        final Queue<float[]> frames = new ConcurrentLinkedQueue<>();
        volatile long lastPush = System.nanoTime();
        boolean primed;
    }

    private final int outputRate;
    private final int outputFrame;

    private volatile float[] params = MixerParam.defaults();
    private volatile LoopMode loopMode = LoopMode.OFF;
    private volatile String status = "";
    private volatile boolean closed;

    // audio thread state
    private float[] left = new float[960];
    private float[] right = new float[960];
    private float[] sub = new float[960];
    private float[] mono = new float[960];
    private long sequence;
    private boolean streaming;

    private volatile float levelLeft;
    private volatile float levelRight;
    private volatile float levelSub;

    MixerSession(GlobalPos key) {
        this.key = key;

        SoundAddon addon = requireAddon();
        ServerConfig config = addon.voiceServer().getConfig();
        this.outputRate = config != null ? config.voice().sampleRate() : 48_000;
        this.outputFrame = outputRate / 50;

        this.player = addon.lava().createPlayer();
        this.player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackStart(AudioPlayer player, AudioTrack track) {
                status = "";
                String title = track.getInfo().title;
                speakers.values().forEach(out -> out.source.setName(title));
            }

            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
                if (!reason.mayStartNext) return;

                if (reason == AudioTrackEndReason.FINISHED) {
                    if (loopMode == LoopMode.TRACK) {
                        player.startTrack(track.makeClone(), false);
                        return;
                    }
                    if (loopMode == LoopMode.QUEUE) {
                        synchronized (MixerSession.this) {
                            queue.addLast(track.makeClone());
                        }
                    }
                }
                playNext();
            }

            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                status = "!" + firstLine(exception.getMessage());
                PvSound.LOGGER.warn("Track {} failed: {}", track.getInfo().title, exception.getMessage());
            }

            @Override
            public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
                status = "!Track stuck, skipping";
                playNext();
            }
        });
    }

    private static String firstLine(String message) {
        if (message == null) return "Playback failed";
        // youtube-source appends one line per client; keep the summary without the "(yts.version: ...)" prefix
        return message.split("\\R")[0].replaceFirst("^\\(yts\\.version: [^)]*\\)\\s*", "");
    }

    private static SoundAddon requireAddon() {
        SoundAddon addon = SoundAddon.get();
        if (addon == null) throw new IllegalStateException("Plasmo Voice server is not running");
        return addon;
    }

    public GlobalPos key() {
        return key;
    }

    // ---------------------------------------------------------------- transport (any thread)

    public synchronized void playNow(List<AudioTrack> tracks) {
        if (tracks.isEmpty()) return;
        for (int i = tracks.size() - 1; i >= 1; i--) queue.addFirst(tracks.get(i));
        trimQueue();
        player.setPaused(false);
        player.startTrack(tracks.get(0), false);
    }

    public synchronized void enqueue(List<AudioTrack> tracks) {
        queue.addAll(tracks);
        trimQueue();
        if (player.getPlayingTrack() == null) playNext();
    }

    public synchronized void playNext() {
        AudioTrack next = queue.pollFirst();
        if (next == null) {
            player.stopTrack();
            return;
        }
        player.startTrack(next, false);
    }

    public synchronized void stop() {
        queue.clear();
        player.stopTrack();
        player.setPaused(false);
    }

    public synchronized void clearQueue() {
        queue.clear();
    }

    public void togglePause() {
        player.setPaused(!player.isPaused());
    }

    public void cycleLoop() {
        loopMode = LoopMode.values()[(loopMode.ordinal() + 1) % LoopMode.values().length];
    }

    public void setLoopMode(LoopMode mode) {
        loopMode = mode;
    }

    public void setParams(float[] params) {
        this.params = params.clone();
    }

    public void setStatus(String status) {
        this.status = status;
    }

    private void trimQueue() {
        int max = SoundConfig.MAX_QUEUE.get();
        while (queue.size() > max) queue.pollLast();
    }

    // ---------------------------------------------------------------- state for UI / redstone

    public boolean isPlaying() {
        return player.getPlayingTrack() != null;
    }

    public boolean isPaused() {
        return player.isPaused();
    }

    public LoopMode loopMode() {
        return loopMode;
    }

    public String status() {
        return status;
    }

    public AudioTrack currentTrack() {
        return player.getPlayingTrack();
    }

    public synchronized List<String> queuePreview(int max) {
        List<String> titles = new ArrayList<>(max);
        for (AudioTrack track : queue) {
            if (titles.size() >= max) break;
            titles.add(track.getInfo().title);
        }
        return titles;
    }

    public synchronized int queueSize() {
        return queue.size();
    }

    public float levelLeft() {
        return levelLeft;
    }

    public float levelRight() {
        return levelRight;
    }

    public float levelSub() {
        return levelSub;
    }

    public float level(SpeakerChannel channel) {
        return switch (channel) {
            case LEFT -> levelLeft;
            case RIGHT -> levelRight;
            case MONO -> Math.max(levelLeft, levelRight);
            case SUB -> levelSub;
        };
    }

    // ---------------------------------------------------------------- speakers (server thread)

    /**
     * Reconciles the voice sources with the speakers currently wired to the mixer.
     */
    public void syncSpeakers(ServerLevel level, Map<BlockPos, SpeakerSpec> wanted) {
        if (closed) return;
        SoundAddon addon = SoundAddon.get();
        if (addon == null) return;

        Set<BlockPos> stale = new HashSet<>(speakers.keySet());
        stale.removeAll(wanted.keySet());
        for (BlockPos pos : stale) {
            SpeakerOutput out = speakers.remove(pos);
            if (out != null) out.source.remove();
        }

        PlasmoVoiceServer voice = addon.voiceServer();
        McServerWorld world = voice.getMinecraftServer().getWorld(level);
        int cone = SoundConfig.SPEAKER_CONE_ANGLE.get();
        AudioTrack current = player.getPlayingTrack();

        wanted.forEach((pos, spec) -> {
            short distance = (short) (spec.subwoofer()
                    ? SoundConfig.SUBWOOFER_RANGE.get().intValue()
                    : SoundConfig.SPEAKER_RANGE.get().intValue());

            SpeakerOutput existing = speakers.get(pos);
            if (existing != null) {
                existing.channel = spec.channel();
                existing.distance = distance;
                existing.source.setAngle(spec.subwoofer() ? 0 : cone);
                return;
            }

            // the cone points out of the speaker's front face; bass is omnidirectional in real life too
            ServerPos3d position = new ServerPos3d(
                    world,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    spec.facing().toYRot(), 0f
            );
            ServerStaticSource source = addon.sourceLine().createStaticSource(position, false);
            if (!spec.subwoofer() && cone > 0) source.setAngle(cone);
            source.setIconVisible(true);
            if (current != null) source.setName(current.getInfo().title);

            speakers.put(pos.immutable(), new SpeakerOutput(source, spec.channel(), spec.subwoofer(), distance));
        });
    }

    public int speakerCount() {
        return speakers.size();
    }

    // ---------------------------------------------------------------- audio thread

    void tick() {
        if (closed) return;

        AudioFrame frame = player.provide();
        int samples = 960;
        if (frame != null) {
            byte[] data = frame.getData();
            samples = data.length / 4;
            ensureCapacity(samples);

            for (int i = 0; i < samples; i++) {
                int o = i * 4;
                left[i] = (short) ((data[o] & 0xFF) | (data[o + 1] << 8)) / 32768f;
                right[i] = (short) ((data[o + 2] & 0xFF) | (data[o + 3] << 8)) / 32768f;
            }
        } else {
            Arrays.fill(left, 0, samples, 0f);
            Arrays.fill(right, 0, samples, 0f);
        }

        boolean micActive = mixMicrophones(samples);
        // keep the stream open briefly after the last voice frame: avoids cutting words on jitter and lets echo ring out
        long now = System.nanoTime();
        // lavaplayer can briefly return no frame while buffering: don't end the stream for that either
        if (frame != null) streamHoldUntil = now + 200_000_000L;
        boolean hold = now < micHoldUntil || now < streamHoldUntil;
        if (frame == null && !micActive && !hold) {
            if (streaming) endStream();
            levelLeft *= 0.8f;
            levelRight *= 0.8f;
            levelSub *= 0.8f;
            return;
        }

        boolean hasSub = false;
        for (SpeakerOutput out : speakers.values()) {
            if (out.channel == SpeakerChannel.SUB) {
                hasSub = true;
                break;
            }
        }

        dsp.process(left, right, hasSub ? sub : null, params);
        updateLevels(samples, hasSub);

        if (speakers.isEmpty()) return;

        SoundAddon addon = SoundAddon.get();
        if (addon == null) return;

        EnumMap<SpeakerChannel, byte[]> packets = new EnumMap<>(SpeakerChannel.class);
        for (SpeakerOutput out : speakers.values()) {
            SpeakerChannel channel = out.channel;
            byte[] packet = packets.get(channel);
            if (packet == null) {
                packet = encode(addon, channel, samples);
                if (packet == null) continue;
                packets.put(channel, packet);
            }
            out.source.sendAudioFrame(packet, sequence, out.distance);
        }
        sequence++;
        streaming = true;
    }

    /**
     * Queues one 20ms voice frame (mono PCM at the voice server sample rate) from a player holding a microphone.
     * Called from the Plasmo Voice UDP thread.
     */
    public void pushMicrophone(UUID player, short[] pcm) {
        if (closed) return;

        // resample to the 48kHz mixer bus
        float[] frame = new float[960];
        double step = (double) pcm.length / frame.length;
        for (int i = 0; i < frame.length; i++) {
            double pos = i * step;
            int idx = (int) pos;
            float a = pcm[Math.min(idx, pcm.length - 1)] / 32768f;
            float b = pcm[Math.min(idx + 1, pcm.length - 1)] / 32768f;
            frame[i] = (float) (a + (b - a) * (pos - idx));
        }

        MicBuffer buffer = microphones.computeIfAbsent(player, id -> new MicBuffer());
        buffer.lastPush = System.nanoTime();
        buffer.frames.add(frame);
        // keep latency bounded: drop the oldest frames if the network bursts
        while (buffer.frames.size() > 8) buffer.frames.poll();
    }

    /** True while someone is talking into a microphone wired to this mixer. */
    public boolean isMicLive() {
        return System.nanoTime() < micHoldUntil;
    }

    private boolean mixMicrophones(int samples) {
        if (microphones.isEmpty()) return false;

        float gain = params[MixerParam.MIC.ordinal()];
        long now = System.nanoTime();
        boolean any = false;
        Iterator<MicBuffer> it = microphones.values().iterator();
        while (it.hasNext()) {
            MicBuffer buffer = it.next();
            if (now - buffer.lastPush > 5_000_000_000L && buffer.frames.isEmpty()) {
                it.remove();
                continue;
            }
            // pre-buffer 2 frames (40ms) before playing, re-prime after an underrun
            if (!buffer.primed) {
                if (buffer.frames.size() < 2) continue;
                buffer.primed = true;
            }
            float[] voice = buffer.frames.poll();
            if (voice == null) {
                buffer.primed = false;
                continue;
            }
            any = true;
            micHoldUntil = now + 400_000_000L;
            int n = Math.min(samples, voice.length);
            for (int i = 0; i < n; i++) {
                float v = voice[i] * gain;
                left[i] += v;
                right[i] += v;
            }
        }
        return any;
    }

    private void endStream() {
        for (SpeakerOutput out : speakers.values()) {
            out.source.sendAudioEnd(sequence, out.distance);
        }
        sequence++;
        streaming = false;
        encoders.values().forEach(AudioEncoder::reset);
    }

    private byte[] encode(SoundAddon addon, SpeakerChannel channel, int samples) {
        float[] signal = switch (channel) {
            case LEFT -> left;
            case RIGHT -> right;
            case SUB -> sub;
            case MONO -> {
                for (int i = 0; i < samples; i++) mono[i] = (left[i] + right[i]) * 0.5f;
                yield mono;
            }
        };

        short[] pcm = toPcm(signal, samples);
        try {
            AudioEncoder encoder = encoders.computeIfAbsent(channel, c -> createEncoder(addon.voiceServer()));
            return addon.voiceServer().getDefaultEncryption().encrypt(encoder.encode(pcm));
        } catch (Exception e) {
            PvSound.LOGGER.error("Failed to encode {} channel", channel, e);
            return null;
        }
    }

    private AudioEncoder createEncoder(PlasmoVoiceServer voice) {
        ServerConfig config = voice.getConfig();
        int mtu = config != null ? config.voice().mtuSize() : 1024;
        return voice.getCodecManager().createEncoder(
                new OpusEncoderInfo(OpusMode.AUDIO, SoundConfig.OPUS_BITRATE.get()),
                outputRate,
                false,
                mtu
        );
    }

    /**
     * Converts a 48kHz float frame to the voice server sample rate (linear interpolation when they differ).
     */
    private short[] toPcm(float[] signal, int samples) {
        short[] pcm = new short[outputFrame];
        if (outputFrame == samples) {
            for (int i = 0; i < samples; i++) pcm[i] = toShort(signal[i]);
            return pcm;
        }

        double step = (double) samples / outputFrame;
        for (int i = 0; i < outputFrame; i++) {
            double pos = i * step;
            int idx = (int) pos;
            double frac = pos - idx;
            float a = signal[Math.min(idx, samples - 1)];
            float b = signal[Math.min(idx + 1, samples - 1)];
            pcm[i] = toShort((float) (a + (b - a) * frac));
        }
        return pcm;
    }

    private static short toShort(float v) {
        int s = Math.round(v * 32767f);
        return (short) Math.max(-32768, Math.min(32767, s));
    }

    private void updateLevels(int samples, boolean hasSub) {
        double sumL = 0, sumR = 0, sumS = 0;
        for (int i = 0; i < samples; i++) {
            sumL += left[i] * left[i];
            sumR += right[i] * right[i];
            if (hasSub) sumS += sub[i] * sub[i];
        }
        // rms -> roughly 0..1 meter value (-48dB..0dB)
        levelLeft = smooth(levelLeft, meter(sumL / samples));
        levelRight = smooth(levelRight, meter(sumR / samples));
        levelSub = hasSub ? smooth(levelSub, meter(sumS / samples)) : 0f;
    }

    private static float meter(double meanSquare) {
        if (meanSquare <= 1e-9) return 0f;
        double db = 10 * Math.log10(meanSquare) + 3; // rms dB, +3 so a full-scale sine reads 0dB
        return (float) Math.max(0, Math.min(1, (db + 48) / 48));
    }

    private static float smooth(float current, float target) {
        return target > current ? target : current * 0.85f + target * 0.15f;
    }

    private void ensureCapacity(int samples) {
        if (left.length >= samples) return;
        left = new float[samples];
        right = new float[samples];
        sub = new float[samples];
        mono = new float[samples];
    }

    // ---------------------------------------------------------------- lifecycle

    void close() {
        if (closed) return;
        closed = true;

        try {
            player.destroy();
        } catch (Throwable ignored) {
        }
        for (SpeakerOutput out : speakers.values()) {
            out.source.remove();
        }
        speakers.clear();
        encoders.values().forEach(AudioEncoder::close);
        encoders.clear();
    }
}
