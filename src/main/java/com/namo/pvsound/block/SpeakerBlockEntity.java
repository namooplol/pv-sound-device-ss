package com.namo.pvsound.block;

import com.namo.pvsound.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which mixer the speaker is wired to. Synced to clients so the cable can be rendered.
 */
public class SpeakerBlockEntity extends BlockEntity {

    private @Nullable BlockPos mixerPos;
    /** cable clips the cable passes through, from the mixer towards the speaker */
    private List<BlockPos> route = List.of();

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.SPEAKER_BE.get(), pos, state);
    }

    public @Nullable BlockPos getMixerPos() {
        return mixerPos;
    }

    public List<BlockPos> getRoute() {
        return route;
    }

    public void setMixerPos(@Nullable BlockPos mixerPos) {
        setMixerPos(mixerPos, List.of());
    }

    public void setMixerPos(@Nullable BlockPos mixerPos, List<BlockPos> route) {
        this.mixerPos = mixerPos == null ? null : mixerPos.immutable();
        this.route = mixerPos == null ? List.of() : List.copyOf(route);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Re-sends this speaker's channel to the mixer it is wired to. */
    public void notifyMixer() {
        MixerBlockEntity mixer = mixer();
        if (mixer != null) mixer.refreshSpeakers();
    }

    /** Called when the speaker block is removed. */
    public void disconnect() {
        MixerBlockEntity mixer = mixer();
        if (mixer != null) mixer.unlink(worldPosition, false);
        mixerPos = null;
        route = List.of();
    }

    private @Nullable MixerBlockEntity mixer() {
        if (level == null || mixerPos == null || !level.isLoaded(mixerPos)) return null;
        return level.getBlockEntity(mixerPos) instanceof MixerBlockEntity mixer ? mixer : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (mixerPos != null) tag.put("Mixer", NbtUtils.writeBlockPos(mixerPos));
        if (!route.isEmpty()) tag.putLongArray("Route", route.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        mixerPos = tag.contains("Mixer") ? NbtUtils.readBlockPos(tag.getCompound("Mixer")) : null;
        List<BlockPos> loaded = new ArrayList<>();
        for (long packed : tag.getLongArray("Route")) loaded.add(BlockPos.of(packed));
        route = List.copyOf(loaded);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public AABB getRenderBoundingBox() {
        AABB box = new AABB(worldPosition);
        if (mixerPos == null) return box;
        box = box.minmax(new AABB(mixerPos));
        for (BlockPos clip : route) box = box.minmax(new AABB(clip));
        return box.inflate(2);
    }
}
