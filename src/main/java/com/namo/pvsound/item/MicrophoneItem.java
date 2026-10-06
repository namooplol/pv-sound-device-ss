package com.namo.pvsound.item;

import com.namo.pvsound.block.MixerBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handheld microphone. Right-click a mixer to pair it, right-click the air to switch it on/off.
 * While it is on and held, the holder's Plasmo Voice audio is mixed into the paired mixer's speakers.
 */
public class MicrophoneItem extends Item {

    private static final String ON = "MicOn";

    public MicrophoneItem(Properties properties) {
        super(properties);
    }

    public static boolean isOn(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().getBoolean(ON);
    }

    public static @Nullable GlobalPos getMixer(ItemStack stack) {
        return LinkedItems.getMixer(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();
        if (player == null || !(level.getBlockState(pos).getBlock() instanceof MixerBlock)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ItemStack stack = ctx.getItemInHand();
        LinkedItems.setMixer(stack, GlobalPos.of(level.dimension(), pos));
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.7f, 1.5f);
        player.displayClientMessage(Component.translatable("pvsound.mic.paired",
                pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        if (getMixer(stack) == null) {
            player.displayClientMessage(Component.translatable("pvsound.mic.not_paired").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }

        boolean on = !isOn(stack);
        stack.getOrCreateTag().putBoolean(ON, on);
        level.playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK, SoundSource.PLAYERS, 0.6f, on ? 1.3f : 0.7f);
        player.displayClientMessage(on
                ? Component.translatable("pvsound.mic.on").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : Component.translatable("pvsound.mic.off").withStyle(ChatFormatting.GRAY), true);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos mixer = getMixer(stack);
        if (mixer == null) {
            tooltip.add(Component.translatable("pvsound.mic.tooltip.unpaired").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos p = mixer.pos();
            tooltip.add(Component.translatable("pvsound.mic.tooltip.paired", p.getX() + ", " + p.getY() + ", " + p.getZ())
                    .withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(isOn(stack)
                ? Component.translatable("pvsound.mic.on").withStyle(ChatFormatting.RED)
                : Component.translatable("pvsound.mic.off").withStyle(ChatFormatting.DARK_GRAY));
    }
}
