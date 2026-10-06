package com.namo.pvsound.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cable clip: mounts on floors, walls and ceilings like a button. Clicking clips with a plugged cable
 * routes the cable through them, so wiring can follow walls and ceilings instead of crossing the room.
 */
public class CableClipBlock extends FaceAttachedHorizontalDirectionalBlock {

    private static final VoxelShape FLOOR = Block.box(5, 0, 5, 11, 4, 11);
    private static final VoxelShape CEILING = Block.box(5, 12, 5, 11, 16, 11);
    private static final VoxelShape NORTH = Block.box(5, 5, 12, 11, 11, 16);
    private static final VoxelShape SOUTH = Block.box(5, 5, 0, 11, 11, 4);
    private static final VoxelShape EAST = Block.box(0, 5, 5, 4, 11, 11);
    private static final VoxelShape WEST = Block.box(12, 5, 5, 16, 11, 11);

    public CableClipBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FACE, AttachFace.WALL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> FLOOR;
            case CEILING -> CEILING;
            case WALL -> switch (state.getValue(FACING)) {
                case SOUTH -> SOUTH;
                case EAST -> EAST;
                case WEST -> WEST;
                default -> NORTH;
            };
        };
    }

    /** World position where the cable passes through the hook. */
    public static Vec3 anchor(BlockState state, BlockPos pos) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        return switch (state.getValue(FACE)) {
            case FLOOR -> new Vec3(x, pos.getY() + 0.15, z);
            case CEILING -> new Vec3(x, pos.getY() + 0.85, z);
            case WALL -> {
                Direction facing = state.getValue(FACING);
                yield new Vec3(x - facing.getStepX() * 0.35, y, z - facing.getStepZ() * 0.35);
            }
        };
    }
}
