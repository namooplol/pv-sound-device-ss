package com.namo.pvsound.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * NBT helpers for items that remember a mixer (cable, microphone), the cable route and cable extensions.
 */
final class LinkedItems {

    private static final String MIXER = "Mixer";
    private static final String DIMENSION = "MixerDim";
    private static final String ROUTE = "Route";
    private static final String EXTENSIONS = "Extensions";

    static @Nullable GlobalPos getMixer(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(MIXER) || !tag.contains(DIMENSION)) return null;

        ResourceLocation dim = ResourceLocation.tryParse(tag.getString(DIMENSION));
        if (dim == null) return null;
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound(MIXER));
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), pos);
    }

    static void setMixer(ItemStack stack, GlobalPos pos) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.put(MIXER, NbtUtils.writeBlockPos(pos.pos()));
        tag.putString(DIMENSION, pos.dimension().location().toString());
        tag.remove(ROUTE);
    }

    static void clearMixer(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        tag.remove(MIXER);
        tag.remove(DIMENSION);
        tag.remove(ROUTE);
    }

    static List<BlockPos> getRoute(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        List<BlockPos> route = new ArrayList<>();
        if (tag == null) return route;
        for (long packed : tag.getLongArray(ROUTE)) route.add(BlockPos.of(packed));
        return route;
    }

    static void setRoute(ItemStack stack, List<BlockPos> route) {
        if (route.isEmpty()) {
            if (stack.getTag() != null) stack.getTag().remove(ROUTE);
            return;
        }
        stack.getOrCreateTag().putLongArray(ROUTE, route.stream().mapToLong(BlockPos::asLong).toArray());
    }

    static int getExtensions(ItemStack stack) {
        return stack.getTag() == null ? 0 : stack.getTag().getInt(EXTENSIONS);
    }

    static void setExtensions(ItemStack stack, int extensions) {
        stack.getOrCreateTag().putInt(EXTENSIONS, extensions);
    }

    private LinkedItems() {
    }
}
