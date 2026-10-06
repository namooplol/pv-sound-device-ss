package com.namo.pvsound.audio;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.SoundAddon;
import com.namo.pvsound.block.MixerBlockEntity;
import com.namo.pvsound.item.MicrophoneItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes the voice of players holding a switched-on microphone into the linked mixer.
 * The set of active microphones is refreshed on the server thread; voice packets arrive on the UDP thread.
 */
public final class MicrophoneRouter {

    private final PlasmoVoiceServer voiceServer;
    private final Map<UUID, GlobalPos> activeMics = new ConcurrentHashMap<>();
    private final Map<UUID, AudioDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, AudioDecoder> stereoDecoders = new ConcurrentHashMap<>();
    /** last time a voice frame was routed, per player (for the LIVE indicator) */
    private final Map<UUID, Long> lastRouted = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastError = new ConcurrentHashMap<>();
    private final Map<UUID, ActivationLock> activationLocks = new ConcurrentHashMap<>();
    private int ticks;

    private static final class ActivationLock {
        final UUID activation;
        volatile long lastSeen = System.currentTimeMillis();

        ActivationLock(UUID activation) {
            this.activation = activation;
        }
    }

    public MicrophoneRouter(PlasmoVoiceServer voiceServer) {
        this.voiceServer = voiceServer;
    }

    /** Server thread, every 5 ticks. */
    public void refresh(MinecraftServer server) {
        ticks++;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            GlobalPos mixer = heldMicrophoneTarget(player);
            if (mixer == null) {
                if (activeMics.remove(uuid) != null) {
                    closeDecoders(uuid);
                    lastRouted.remove(uuid);
                }
                continue;
            }

            if (activeMics.put(uuid, mixer) == null) {
                PvSound.LOGGER.info("Microphone of {} is live on mixer {}", player.getGameProfile().getName(), mixer);
            }

            ServerLevel level = server.getLevel(mixer.dimension());
            boolean mixerLoaded = level != null && level.isLoaded(mixer.pos())
                    && level.getBlockEntity(mixer.pos()) instanceof MixerBlockEntity;
            if (mixerLoaded) ((MixerBlockEntity) level.getBlockEntity(mixer.pos())).ensureSession();

            if (ticks % 4 == 0) player.displayClientMessage(liveStatus(uuid, mixerLoaded), true);
        }
        activeMics.keySet().removeIf(uuid -> {
            boolean gone = server.getPlayerList().getPlayer(uuid) == null;
            if (gone) closeDecoders(uuid);
            return gone;
        });
    }

    private Component liveStatus(UUID uuid, boolean mixerLoaded) {
        if (!mixerLoaded) {
            return Component.translatable("pvsound.mic.status.no_mixer").withStyle(ChatFormatting.RED);
        }
        Long last = lastRouted.get(uuid);
        boolean talking = last != null && System.currentTimeMillis() - last < 600;
        return talking
                ? Component.translatable("pvsound.mic.status.talking").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : Component.translatable("pvsound.mic.status.idle").withStyle(ChatFormatting.GOLD);
    }

    private static GlobalPos heldMicrophoneTarget(ServerPlayer player) {
        for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (!(stack.getItem() instanceof MicrophoneItem) || !MicrophoneItem.isOn(stack)) continue;

            GlobalPos target = MicrophoneItem.getMixer(stack);
            if (target != null && target.dimension().equals(player.level().dimension())) return target;
        }
        return null;
    }

    public boolean isActive(UUID player) {
        return activeMics.containsKey(player);
    }

    // Plasmo Voice cancels PlayerSpeakEvent once its own activation handled the packet, so listen to cancelled events too
    @EventSubscribe(ignoreCancelled = false)
    public void onPlayerSpeak(PlayerSpeakEvent event) {
        UUID uuid = event.getPlayer().getInstance().getUuid();
        GlobalPos target = activeMics.get(uuid);
        if (target == null) return;

        SoundAddon addon = SoundAddon.get();
        if (addon == null) return;
        MixerSession session = addon.engine().get(target);
        if (session == null) return;

        PlayerAudioPacket packet = event.getPacket();
        // the client sends one packet per active activation (proximity, groups, other addons):
        // follow a single activation stream, otherwise frames get doubled and the decoder state breaks
        long now = System.currentTimeMillis();
        UUID activation = packet.getActivationId();
        ActivationLock lock = activationLocks.get(uuid);
        if (lock == null || (!lock.activation.equals(activation) && now - lock.lastSeen > 1_000)) {
            lock = new ActivationLock(activation);
            activationLocks.put(uuid, lock);
        }
        if (!lock.activation.equals(activation)) return;
        lock.lastSeen = now;

        try {
            byte[] opus = voiceServer.getDefaultEncryption().decrypt(packet.getData());
            AudioDecoder decoder = (packet.isStereo() ? stereoDecoders : decoders)
                    .computeIfAbsent(uuid, id -> voiceServer.createOpusDecoder(packet.isStereo()));

            short[] pcm;
            synchronized (decoder) {
                pcm = decoder.decode(opus);
            }
            if (pcm == null || pcm.length == 0) return;
            if (packet.isStereo()) pcm = downmix(pcm);

            session.pushMicrophone(uuid, pcm);
            lastRouted.put(uuid, System.currentTimeMillis());
        } catch (Exception e) {
            Long previous = lastError.put(uuid, now);
            if (previous == null || now - previous > 10_000) {
                PvSound.LOGGER.warn("Failed to route microphone audio of {}", uuid, e);
            }
        }
    }

    private static short[] downmix(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        }
        return mono;
    }

    private void closeDecoders(UUID uuid) {
        activationLocks.remove(uuid);
        AudioDecoder mono = decoders.remove(uuid);
        if (mono != null) mono.close();
        AudioDecoder stereo = stereoDecoders.remove(uuid);
        if (stereo != null) stereo.close();
    }

    public void shutdown() {
        activeMics.clear();
        decoders.values().forEach(AudioDecoder::close);
        stereoDecoders.values().forEach(AudioDecoder::close);
        decoders.clear();
        stereoDecoders.clear();
    }
}
