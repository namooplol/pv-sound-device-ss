package com.namo.pvsound.block;

import com.namo.pvsound.audio.SpeakerChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Bookshelf speaker (channel L/R/MONO, right-click to cycle) or subwoofer (always SUB).
 */
public class SpeakerBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<SpeakerChannel> CHANNEL = EnumProperty.create("channel", SpeakerChannel.class);

    private static final VoxelShape SHAPE_NS = Block.box(2, 0, 3, 14, 16, 13);
    private static final VoxelShape SHAPE_EW = Block.box(3, 0, 2, 13, 16, 14);

    private final boolean subwoofer;

    public SpeakerBlock(boolean subwoofer, Properties properties) {
        super(properties);
        this.subwoofer = subwoofer;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CHANNEL, subwoofer ? SpeakerChannel.SUB : SpeakerChannel.MONO));
    }

    public boolean isSubwoofer() {
        return subwoofer;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CHANNEL);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (subwoofer) return Shapes.block();
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.getItemInHand(hand).isEmpty() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        SpeakerBlockEntity speaker = level.getBlockEntity(pos) instanceof SpeakerBlockEntity be ? be : null;
        if (speaker == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown() || subwoofer) {
            player.displayClientMessage(describe(state, speaker), true);
            return InteractionResult.CONSUME;
        }

        SpeakerChannel next = state.getValue(CHANNEL).next();
        level.setBlock(pos, state.setValue(CHANNEL, next), Block.UPDATE_ALL);
        float pitch = switch (next) {
            case LEFT -> 0.8f;
            case RIGHT -> 1.2f;
            default -> 1.0f;
        };
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, pitch);
        player.displayClientMessage(Component.translatable("pvsound.speaker.channel",
                Component.translatable(next.translationKey()).withStyle(next.color, ChatFormatting.BOLD)), true);

        speaker.notifyMixer();
        return InteractionResult.CONSUME;
    }

    private static Component describe(BlockState state, SpeakerBlockEntity speaker) {
        SpeakerChannel channel = state.getValue(CHANNEL);
        Component channelText = Component.translatable(channel.translationKey()).withStyle(channel.color);
        BlockPos mixer = speaker.getMixerPos();
        if (mixer == null) {
            return Component.translatable("pvsound.speaker.info.unlinked", channelText);
        }
        return Component.translatable("pvsound.speaker.info.linked", channelText,
                mixer.getX() + ", " + mixer.getY() + ", " + mixer.getZ());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
                speaker.disconnect();
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
