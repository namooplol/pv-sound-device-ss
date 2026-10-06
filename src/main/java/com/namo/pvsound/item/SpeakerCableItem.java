package com.namo.pvsound.item;

import com.namo.pvsound.SoundConfig;
import com.namo.pvsound.block.CableClipBlock;
import com.namo.pvsound.block.MixerBlock;
import com.namo.pvsound.block.MixerBlockEntity;
import com.namo.pvsound.block.SpeakerBlock;
import com.namo.pvsound.block.SpeakerBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Speaker cable spool.
 * <ul>
 *     <li>Right-click a mixer: plug the cable into it.</li>
 *     <li>Right-click cable clips: route the cable through them (sneak to take the last clip back).</li>
 *     <li>Right-click a speaker: wire it to the plugged mixer along the route (length is limited).</li>
 *     <li>Sneak + right-click a mixer: auto-wire every free speaker nearby, L/R picked from where you look.</li>
 *     <li>Sneak + right-click a speaker: unplug it. Sneak + right-click air: clear the route, then reel the cable in.</li>
 * </ul>
 */
public class SpeakerCableItem extends Item {

    public SpeakerCableItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        if (player == null) return InteractionResult.PASS;

        Block block = level.getBlockState(pos).getBlock();
        if (!(block instanceof MixerBlock) && !(block instanceof SpeakerBlock) && !(block instanceof CableClipBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (block instanceof MixerBlock) onMixer((ServerPlayer) player, stack, level, pos);
        else if (block instanceof CableClipBlock) onClip(player, stack, level, pos);
        else onSpeaker(player, stack, (ServerLevel) level, pos);
        return InteractionResult.CONSUME;
    }

    private void onMixer(ServerPlayer player, ItemStack stack, Level level, BlockPos pos) {
        if (player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof MixerBlockEntity be) {
            int linked = be.autoLink(player, CableRules.maxLength(stack));
            player.displayClientMessage(Component.translatable("pvsound.cable.auto", linked)
                    .withStyle(linked > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
            level.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
            return;
        }

        LinkedItems.setMixer(stack, GlobalPos.of(level.dimension(), pos));
        level.playSound(null, pos, SoundEvents.TRIPWIRE_ATTACH, SoundSource.BLOCKS, 1f, 1.4f);
        player.displayClientMessage(Component.translatable("pvsound.cable.plugged").withStyle(ChatFormatting.YELLOW), true);
    }

    private void onClip(Player player, ItemStack stack, Level level, BlockPos pos) {
        GlobalPos mixer = getMixer(stack);
        if (mixer == null || !mixer.dimension().equals(level.dimension())) {
            player.displayClientMessage(Component.translatable("pvsound.cable.no_mixer").withStyle(ChatFormatting.RED), true);
            return;
        }

        List<BlockPos> route = new ArrayList<>(LinkedItems.getRoute(stack));
        if (player.isShiftKeyDown()) {
            if (route.remove(pos.immutable())) {
                LinkedItems.setRoute(stack, route);
                level.playSound(null, pos, SoundEvents.TRIPWIRE_DETACH, SoundSource.BLOCKS, 0.8f, 1.2f);
                player.displayClientMessage(Component.translatable("pvsound.clip.removed", route.size()), true);
            }
            return;
        }
        if (route.contains(pos)) {
            player.displayClientMessage(Component.translatable("pvsound.clip.already").withStyle(ChatFormatting.GRAY), true);
            return;
        }

        int max = CableRules.maxLength(stack);
        route.add(pos.immutable());
        double length = CableRules.pathLength(level, mixer.pos(), route.subList(0, route.size() - 1), CableRules.anchor(level, pos));
        if (length > max) {
            player.displayClientMessage(Component.translatable("pvsound.cable.too_long",
                    String.format("%.1f", length), max).withStyle(ChatFormatting.RED), true);
            return;
        }

        LinkedItems.setRoute(stack, route);
        level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 0.8f, 1.5f);
        player.displayClientMessage(Component.translatable("pvsound.clip.added", route.size(),
                String.format("%.1f", length), max).withStyle(ChatFormatting.AQUA), true);
    }

    private void onSpeaker(Player player, ItemStack stack, ServerLevel level, BlockPos pos) {
        if (player.isShiftKeyDown()) {
            if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity be && be.getMixerPos() != null) {
                BlockPos mixerPos = be.getMixerPos();
                if (level.isLoaded(mixerPos) && level.getBlockEntity(mixerPos) instanceof MixerBlockEntity mixerBe) {
                    mixerBe.unlink(pos, true);
                } else {
                    be.setMixerPos(null);
                }
                level.playSound(null, pos, SoundEvents.TRIPWIRE_DETACH, SoundSource.BLOCKS, 1f, 1f);
                player.displayClientMessage(Component.translatable("pvsound.cable.unplugged"), true);
            }
            return;
        }

        GlobalPos target = getMixer(stack);
        if (target == null) {
            player.displayClientMessage(Component.translatable("pvsound.cable.no_mixer").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!target.dimension().equals(level.dimension())) {
            player.displayClientMessage(Component.translatable("pvsound.cable.other_dimension").withStyle(ChatFormatting.RED), true);
            return;
        }

        BlockPos mixerPos = target.pos();
        List<BlockPos> route = LinkedItems.getRoute(stack);
        double length = CableRules.pathLength(level, mixerPos, route, Vec3.atCenterOf(pos));
        int max = CableRules.maxLength(stack);
        if (length > max) {
            player.displayClientMessage(Component.translatable("pvsound.cable.too_long",
                    String.format("%.1f", length), max).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!(level.getBlockEntity(mixerPos) instanceof MixerBlockEntity mixerBe)) {
            LinkedItems.clearMixer(stack);
            player.displayClientMessage(Component.translatable("pvsound.cable.mixer_gone").withStyle(ChatFormatting.RED), true);
            return;
        }

        switch (mixerBe.link(pos, route)) {
            case LINKED -> {
                level.playSound(null, pos, SoundEvents.TRIPWIRE_ATTACH, SoundSource.BLOCKS, 1f, 1.0f);
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.5f, 1.6f);
                sparkAlongCable(level, mixerPos, route, pos);
                player.displayClientMessage(Component.translatable("pvsound.cable.linked",
                        String.format("%.1f", length), max).withStyle(ChatFormatting.GREEN), true);
            }
            case REROUTED -> {
                level.playSound(null, pos, SoundEvents.TRIPWIRE_ATTACH, SoundSource.BLOCKS, 1f, 1.2f);
                sparkAlongCable(level, mixerPos, route, pos);
                player.displayClientMessage(Component.translatable("pvsound.cable.rerouted",
                        String.format("%.1f", length), max).withStyle(ChatFormatting.AQUA), true);
            }
            case FULL -> player.displayClientMessage(Component.translatable("pvsound.cable.full",
                    SoundConfig.MAX_SPEAKERS_PER_MIXER.get()).withStyle(ChatFormatting.RED), true);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || getMixer(stack) == null) return InteractionResultHolder.pass(stack);

        if (!level.isClientSide) {
            if (!LinkedItems.getRoute(stack).isEmpty()) {
                LinkedItems.setRoute(stack, List.of());
                player.displayClientMessage(Component.translatable("pvsound.clip.cleared"), true);
            } else {
                LinkedItems.clearMixer(stack);
                player.displayClientMessage(Component.translatable("pvsound.cable.reeled"), true);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1f, 0.8f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** A little electric spark running down the new cable. */
    private static void sparkAlongCable(ServerLevel level, BlockPos mixer, List<BlockPos> route, BlockPos speaker) {
        List<Vec3> points = new ArrayList<>();
        points.add(Vec3.atCenterOf(mixer));
        for (BlockPos clip : route) points.add(CableRules.anchor(level, clip));
        points.add(Vec3.atCenterOf(speaker));

        DustParticleOptions dust = new DustParticleOptions(new Vector3f(1f, 0.85f, 0.2f), 0.8f);
        for (int s = 0; s < points.size() - 1; s++) {
            Vec3 a = points.get(s);
            Vec3 b = points.get(s + 1);
            int steps = (int) Math.max(2, a.distanceTo(b) * 3);
            for (int i = 0; i <= steps; i++) {
                Vec3 p = a.lerp(b, i / (double) steps);
                level.sendParticles(dust, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getMixer(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int extensions = LinkedItems.getExtensions(stack);
        tooltip.add(Component.translatable("pvsound.cable.tooltip.length", CableRules.maxLength(stack), extensions)
                .withStyle(ChatFormatting.AQUA));

        GlobalPos mixer = getMixer(stack);
        if (mixer != null) {
            BlockPos p = mixer.pos();
            tooltip.add(Component.translatable("pvsound.cable.tooltip.plugged", p.getX() + ", " + p.getY() + ", " + p.getZ())
                    .withStyle(ChatFormatting.YELLOW));
            int clips = LinkedItems.getRoute(stack).size();
            if (clips > 0) tooltip.add(Component.translatable("pvsound.cable.tooltip.route", clips).withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("pvsound.cable.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("pvsound.cable.tooltip.4").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("pvsound.cable.tooltip.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("pvsound.cable.tooltip.3").withStyle(ChatFormatting.GRAY));
    }

    public static @Nullable GlobalPos getMixer(ItemStack stack) {
        return LinkedItems.getMixer(stack);
    }

    public static List<BlockPos> getRoute(ItemStack stack) {
        return LinkedItems.getRoute(stack);
    }
}
