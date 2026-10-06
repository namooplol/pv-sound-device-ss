package com.namo.pvsound.block;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.SoundAddon;
import com.namo.pvsound.SoundConfig;
import com.namo.pvsound.audio.MixerParam;
import com.namo.pvsound.audio.MixerSession;
import com.namo.pvsound.audio.SpeakerChannel;
import com.namo.pvsound.network.MixerState;
import com.namo.pvsound.network.PvSoundNetwork;
import com.namo.pvsound.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side mixer: persistent knob values + speaker wiring, and the bridge to the audio session.
 */
public class MixerBlockEntity extends BlockEntity {

    public enum LinkResult { LINKED, REROUTED, FULL }

    private final float[] params = MixerParam.defaults();
    private MixerSession.LoopMode loopMode = MixerSession.LoopMode.OFF;
    private final Set<BlockPos> speakers = new LinkedHashSet<>();

    // runtime only
    private final Map<BlockPos, MixerSession.SpeakerSpec> specs = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private boolean speakersDirty = true;
    private int comparator;
    private int ticks;
    private int settingsVersion = -1;

    public MixerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.MIXER_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ session

    private GlobalPos key() {
        return GlobalPos.of(level.dimension(), worldPosition);
    }

    private @Nullable MixerSession session(boolean create) {
        if (level == null || level.isClientSide) return null;
        SoundAddon addon = SoundAddon.get();
        if (addon == null) return null;

        MixerSession session = addon.engine().get(key());
        if (session != null || !create) return session;

        session = addon.engine().getOrCreate(key());
        session.setParams(params);
        session.setLoopMode(loopMode);
        syncSpeakers(session);
        return session;
    }

    private void syncSpeakers(MixerSession session) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        Map<BlockPos, MixerSession.SpeakerSpec> wanted = new HashMap<>();
        for (BlockPos pos : speakers) {
            MixerSession.SpeakerSpec spec = specs.get(pos);
            if (spec != null) wanted.put(pos, spec);
        }
        session.syncSpeakers(serverLevel, wanted);
        speakersDirty = false;
    }

    /** Re-reads the speakers' block states (channel/facing) and drops speakers that no longer exist. */
    private void validateSpeakers() {
        Iterator<BlockPos> it = speakers.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue; // keep the cached spec, the chunk will come back

            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof SpeakerBlock speakerBlock)
                    || !(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)
                    || !worldPosition.equals(speaker.getMixerPos())) {
                it.remove();
                specs.remove(pos);
                changed = true;
                continue;
            }

            MixerSession.SpeakerSpec spec = new MixerSession.SpeakerSpec(
                    speakerBlock.isSubwoofer() ? SpeakerChannel.SUB : state.getValue(SpeakerBlock.CHANNEL),
                    state.getValue(SpeakerBlock.FACING),
                    speakerBlock.isSubwoofer());
            if (!spec.equals(specs.put(pos, spec))) speakersDirty = true;
        }
        if (changed) {
            speakersDirty = true;
            setChanged();
        }
    }

    /** Makes sure the audio session exists (e.g. a microphone is live before any music was played). */
    public void ensureSession() {
        session(true);
    }

    public void refreshSpeakers() {
        validateSpeakers();
        MixerSession session = session(false);
        if (session != null) syncSpeakers(session);
    }

    // ------------------------------------------------------------------ wiring

    public LinkResult link(BlockPos speakerPos) {
        return link(speakerPos, List.of());
    }

    /**
     * Wires a speaker to this mixer. Linking an already wired speaker again just changes its cable route.
     */
    public LinkResult link(BlockPos speakerPos, List<BlockPos> route) {
        BlockPos pos = speakerPos.immutable();
        if (speakers.contains(pos)) {
            if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) speaker.setMixerPos(worldPosition, route);
            return LinkResult.REROUTED;
        }
        if (speakers.size() >= SoundConfig.MAX_SPEAKERS_PER_MIXER.get()) return LinkResult.FULL;

        if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            BlockPos previous = speaker.getMixerPos();
            if (previous != null && !previous.equals(worldPosition)
                    && level.isLoaded(previous) && level.getBlockEntity(previous) instanceof MixerBlockEntity old) {
                old.unlink(pos, false);
            }
            speaker.setMixerPos(worldPosition, route);
        }

        speakers.add(pos);
        setChanged();
        refreshSpeakers();
        return LinkResult.LINKED;
    }

    public void unlink(BlockPos speakerPos, boolean clearSpeaker) {
        if (!speakers.remove(speakerPos)) return;
        specs.remove(speakerPos);

        if (clearSpeaker && level.isLoaded(speakerPos)
                && level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                && worldPosition.equals(speaker.getMixerPos())) {
            speaker.setMixerPos(null);
        }

        setChanged();
        speakersDirty = true;
        MixerSession session = session(false);
        if (session != null) syncSpeakers(session);
    }

    public boolean isLinked(BlockPos speakerPos) {
        return speakers.contains(speakerPos);
    }

    /**
     * Wires every free speaker around the mixer and assigns L/R from where the player is looking:
     * speakers on the player's left become LEFT, on the right become RIGHT, in the middle MONO.
     *
     * @return number of newly linked speakers
     */
    public int autoLink(ServerPlayer player, int cableLength) {
        int radius = Math.min(cableLength, 32);
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);

        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(worldPosition.offset(-radius, -radius / 2, -radius),
                worldPosition.offset(radius, radius / 2, radius))) {
            if (!(level.getBlockState(pos).getBlock() instanceof SpeakerBlock)) continue;
            if (pos.distSqr(worldPosition) > (double) radius * radius) continue;
            found.add(pos.immutable());
        }

        int linked = 0;
        for (BlockPos pos : found) {
            if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) continue;
            BlockPos owner = speaker.getMixerPos();
            if (owner != null && !owner.equals(worldPosition)) continue; // belongs to another mixer

            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof SpeakerBlock block && !block.isSubwoofer()) {
                double side = (pos.getX() + 0.5 - player.getX()) * rightX + (pos.getZ() + 0.5 - player.getZ()) * rightZ;
                SpeakerChannel channel = Math.abs(side) < 1.5 ? SpeakerChannel.MONO
                        : side > 0 ? SpeakerChannel.RIGHT : SpeakerChannel.LEFT;
                level.setBlock(pos, state.setValue(SpeakerBlock.CHANNEL, channel), Block.UPDATE_ALL);
            }

            if (speakers.contains(pos)) continue;
            LinkResult result = link(pos);
            if (result == LinkResult.LINKED) linked++;
            if (result == LinkResult.FULL) break;
        }
        refreshSpeakers();
        return linked;
    }

    // ------------------------------------------------------------------ controls

    public void openFor(ServerPlayer player) {
        viewers.add(player.getUUID());
        PvSoundNetwork.sendState(player, worldPosition, true, buildState());
    }

    public void handleAction(ServerPlayer player, PvSoundNetwork.Action action, String arg) {
        if (action == PvSoundNetwork.Action.CLOSE) {
            viewers.remove(player.getUUID());
            return;
        }

        SoundAddon addon = SoundAddon.get();
        MixerSession session = session(true);
        if (addon == null || session == null) {
            player.displayClientMessage(Component.translatable("pvsound.mixer.not_ready"), true);
            return;
        }

        switch (action) {
            case PLAY_NOW, ENQUEUE -> {
                String input = arg.trim();
                if (input.isEmpty()) return;

                boolean now = action == PvSoundNetwork.Action.PLAY_NOW;
                session.setStatus("…" + input);
                PvSound.LOGGER.info("{} requested {} on mixer {}", player.getGameProfile().getName(), input, worldPosition);

                addon.lava().load(input).whenComplete((result, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        session.setStatus("!" + cause.getMessage());
                        return;
                    }
                    List<AudioTrack> tracks = result.tracks();
                    if (now) session.playNow(tracks);
                    else session.enqueue(tracks);
                    session.setStatus(result.playlistName() != null
                            ? "+" + tracks.size() + " ♪ " + result.playlistName()
                            : (now ? "" : "+ " + tracks.get(0).getInfo().title));
                });
            }
            case PAUSE -> session.togglePause();
            case SKIP -> session.playNext();
            case STOP -> session.stop();
            case LOOP -> {
                session.cycleLoop();
                loopMode = session.loopMode();
                setChanged();
            }
            case CLEAR_QUEUE -> session.clearQueue();
            default -> {
            }
        }
        pushState();
    }

    public void setParam(int id, float value) {
        MixerParam param = MixerParam.byId(id);
        if (param == null) return;
        params[id] = param.clamp(value);
        setChanged();

        MixerSession session = session(false);
        if (session != null) session.setParams(params);
    }

    private MixerState buildState() {
        MixerSession session = session(false);
        int[] channels = new int[SpeakerChannel.values().length];
        for (BlockPos pos : speakers) {
            MixerSession.SpeakerSpec spec = specs.get(pos);
            if (spec != null) channels[spec.channel().ordinal()]++;
        }

        if (session == null) {
            return new MixerState(params.clone(), loopMode.ordinal(), false, false, "", "", 0, 0,
                    List.of(), 0, 0, 0, 0, SoundAddon.get() == null ? "!Plasmo Voice not ready" : "", channels);
        }

        AudioTrack track = session.currentTrack();
        return new MixerState(
                params.clone(),
                session.loopMode().ordinal(),
                track != null,
                session.isPaused(),
                track == null ? "" : MixerState.clip(track.getInfo().title, 200),
                track == null ? "" : MixerState.clip(track.getInfo().author, 200),
                track == null ? 0 : track.getPosition(),
                track == null || track.getInfo().isStream ? 0 : track.getDuration(),
                session.queuePreview(6).stream().map(t -> MixerState.clip(t, 200)).toList(),
                session.queueSize(),
                session.levelLeft(),
                session.levelRight(),
                session.levelSub(),
                MixerState.clip(session.status(), 400),
                channels
        );
    }

    private void pushState() {
        if (viewers.isEmpty() || !(level instanceof ServerLevel serverLevel)) return;
        MixerState state = buildState();
        viewers.removeIf(uuid -> {
            ServerPlayer player = (ServerPlayer) serverLevel.getPlayerByUUID(uuid);
            if (player == null || player.distanceToSqr(worldPosition.getCenter()) > 12 * 12) return true;
            PvSoundNetwork.sendState(player, worldPosition, false, state);
            return false;
        });
    }

    public int comparatorLevel() {
        return comparator;
    }

    // ------------------------------------------------------------------ ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, MixerBlockEntity mixer) {
        mixer.ticks++;

        if (mixer.ticks % 20 == 1) mixer.validateSpeakers();

        SoundAddon addon = SoundAddon.get();
        if (addon != null && addon.settingsVersion() != mixer.settingsVersion) {
            mixer.settingsVersion = addon.settingsVersion();
            mixer.speakersDirty = true; // ranges / cone angle changed in /pvsound setting
        }

        MixerSession session = mixer.session(false);
        if (session != null && mixer.speakersDirty) mixer.syncSpeakers(session);

        boolean playing = session != null && session.isPlaying() && !session.isPaused();
        if (state.getValue(MixerBlock.PLAYING) != playing) {
            level.setBlock(pos, state.setValue(MixerBlock.PLAYING, playing), Block.UPDATE_ALL);
        }

        int signal = playing ? Math.round(Math.max(session.levelLeft(), session.levelRight()) * 15f) : 0;
        if (signal != mixer.comparator) {
            mixer.comparator = signal;
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }

        if (playing && mixer.ticks % 4 == 0 && level instanceof ServerLevel serverLevel) {
            mixer.spawnSpeakerParticles(serverLevel, session);
        }

        if (mixer.ticks % 3 == 0) mixer.pushState();
    }

    private void spawnSpeakerParticles(ServerLevel serverLevel, MixerSession session) {
        for (Map.Entry<BlockPos, MixerSession.SpeakerSpec> entry : specs.entrySet()) {
            BlockPos pos = entry.getKey();
            MixerSession.SpeakerSpec spec = entry.getValue();
            float lvl = session.level(spec.channel());
            if (lvl < 0.45f || serverLevel.random.nextFloat() > lvl * 0.8f) continue;
            if (!serverLevel.isLoaded(pos)) continue;

            Direction facing = spec.facing();
            double x = pos.getX() + 0.5 + facing.getStepX() * 0.6;
            double y = pos.getY() + 0.5 + serverLevel.random.nextDouble() * 0.5;
            double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.6;
            // count 0: the x speed is used as the note color
            serverLevel.sendParticles(ParticleTypes.NOTE, x, y, z, 0, serverLevel.random.nextInt(25) / 24.0, 0, 0, 1);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void onBroken() {
        for (BlockPos pos : List.copyOf(speakers)) unlink(pos, true);
        stopSession();
    }

    private void stopSession() {
        if (level == null || level.isClientSide) return;
        SoundAddon addon = SoundAddon.get();
        if (addon != null) addon.engine().remove(key());
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        stopSession(); // chunk unloaded or block broken
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag knobs = new CompoundTag();
        for (MixerParam param : MixerParam.values()) knobs.putFloat(param.key, params[param.ordinal()]);
        tag.put("Params", knobs);
        tag.putString("Loop", loopMode.name());
        tag.putLongArray("Speakers", speakers.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        CompoundTag knobs = tag.getCompound("Params");
        for (MixerParam param : MixerParam.values()) {
            params[param.ordinal()] = knobs.contains(param.key) ? param.clamp(knobs.getFloat(param.key)) : param.def;
        }
        try {
            loopMode = MixerSession.LoopMode.valueOf(tag.getString("Loop"));
        } catch (IllegalArgumentException e) {
            loopMode = MixerSession.LoopMode.OFF;
        }
        speakers.clear();
        for (long packed : tag.getLongArray("Speakers")) speakers.add(BlockPos.of(packed));
        speakersDirty = true;
    }
}
