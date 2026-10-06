package com.namo.pvsound.registry;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.block.CableClipBlock;
import com.namo.pvsound.block.MixerBlock;
import com.namo.pvsound.block.MixerBlockEntity;
import com.namo.pvsound.block.SpeakerBlock;
import com.namo.pvsound.block.SpeakerBlockEntity;
import com.namo.pvsound.item.CableExtensionItem;
import com.namo.pvsound.item.MicrophoneItem;
import com.namo.pvsound.item.SpeakerCableItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public final class ModRegistry {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, PvSound.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, PvSound.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, PvSound.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PvSound.MOD_ID);

    public static final RegistryObject<SpeakerBlock> SPEAKER = block("speaker",
            () -> new SpeakerBlock(false, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final RegistryObject<SpeakerBlock> SUBWOOFER = block("subwoofer",
            () -> new SpeakerBlock(true, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK).strength(2f).sound(SoundType.WOOD)));
    public static final RegistryObject<MixerBlock> MIXER = block("mixer",
            () -> new MixerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(2f).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(state -> state.getValue(MixerBlock.PLAYING) ? 6 : 0)));

    public static final RegistryObject<CableClipBlock> CABLE_CLIP = block("cable_clip",
            () -> new CableClipBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(0.3f).sound(SoundType.CHAIN).noCollission().noOcclusion()));

    public static final RegistryObject<SpeakerCableItem> SPEAKER_CABLE = ITEMS.register("speaker_cable",
            () -> new SpeakerCableItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<CableExtensionItem> CABLE_EXTENSION = ITEMS.register("cable_extension",
            () -> new CableExtensionItem(new Item.Properties()));
    public static final RegistryObject<MicrophoneItem> MICROPHONE = ITEMS.register("microphone",
            () -> new MicrophoneItem(new Item.Properties().stacksTo(1)));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<SpeakerBlockEntity>> SPEAKER_BE = BLOCK_ENTITIES.register("speaker",
            () -> BlockEntityType.Builder.of(SpeakerBlockEntity::new, SPEAKER.get(), SUBWOOFER.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<MixerBlockEntity>> MIXER_BE = BLOCK_ENTITIES.register("mixer",
            () -> BlockEntityType.Builder.of(MixerBlockEntity::new, MIXER.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.pvsound"))
            .icon(() -> new ItemStack(SPEAKER.get()))
            .displayItems((params, output) -> {
                output.accept(MIXER.get());
                output.accept(SPEAKER.get());
                output.accept(SUBWOOFER.get());
                output.accept(SPEAKER_CABLE.get());
                output.accept(CABLE_EXTENSION.get());
                output.accept(CABLE_CLIP.get());
                output.accept(MICROPHONE.get());
            })
            .build());

    private static <T extends Block> RegistryObject<T> block(String name, Supplier<T> factory) {
        RegistryObject<T> block = BLOCKS.register(name, factory);
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    private ModRegistry() {
    }
}
