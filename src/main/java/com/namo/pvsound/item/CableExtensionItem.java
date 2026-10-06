package com.namo.pvsound.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Cable extension: hold a speaker cable in the other hand and right-click to splice it on (+length).
 */
public class CableExtensionItem extends Item {

    public CableExtensionItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack extension = player.getItemInHand(hand);
        ItemStack cable = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);

        if (!(cable.getItem() instanceof SpeakerCableItem)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("pvsound.extension.need_cable").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(extension);
        }

        int extensions = LinkedItems.getExtensions(cable);
        if (extensions >= CableRules.maxExtensions) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("pvsound.extension.max", CableRules.maxLength(cable))
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(extension);
        }

        if (!level.isClientSide) {
            LinkedItems.setExtensions(cable, extensions + 1);
            if (!player.getAbilities().instabuild) extension.shrink(1);
            level.playSound(null, player.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 0.8f, 1.3f);
            player.displayClientMessage(Component.translatable("pvsound.extension.added", CableRules.maxLength(cable))
                    .withStyle(ChatFormatting.GREEN), true);
        }
        return InteractionResultHolder.sidedSuccess(extension, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("pvsound.extension.tooltip", CableRules.extensionLength).withStyle(ChatFormatting.GRAY));
    }
}
