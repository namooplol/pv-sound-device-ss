package com.namo.pvsound.item;

import com.namo.pvsound.SoundConfig;
import com.namo.pvsound.block.CableClipBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cable length rules shared by server logic and client previews.
 * The server fills these from its config; clients receive them in {@code ServerRulesS2C}.
 */
public final class CableRules {

    public static volatile int baseLength = 24;
    public static volatile int extensionLength = 16;
    public static volatile int maxExtensions = 8;

    public static void loadFromConfig() {
        baseLength = SoundConfig.CABLE_MAX_LENGTH.get();
        extensionLength = SoundConfig.CABLE_EXTENSION_LENGTH.get();
        maxExtensions = SoundConfig.MAX_CABLE_EXTENSIONS.get();
    }

    public static int maxLength(ItemStack cable) {
        return baseLength + Math.min(LinkedItems.getExtensions(cable), maxExtensions) * extensionLength;
    }

    /** Point where a cable attaches to the block at {@code pos} (clips hold it close to their mounting face). */
    public static Vec3 anchor(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CableClipBlock) return CableClipBlock.anchor(state, pos);
        return Vec3.atCenterOf(pos);
    }

    /** Cable length from the mixer through every clip to the end point. */
    public static double pathLength(BlockGetter level, BlockPos mixer, List<BlockPos> route, Vec3 end) {
        Vec3 previous = Vec3.atCenterOf(mixer);
        double length = 0;
        for (BlockPos clip : route) {
            Vec3 p = anchor(level, clip);
            length += previous.distanceTo(p);
            previous = p;
        }
        return length + previous.distanceTo(end);
    }

    private CableRules() {
    }
}
