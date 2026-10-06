package com.namo.pvsound;

import com.mojang.logging.LogUtils;
import com.namo.pvsound.network.PvSoundNetwork;
import com.namo.pvsound.registry.ModRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import su.plo.voice.api.server.PlasmoVoiceServer;

@Mod(PvSound.MOD_ID)
public final class PvSound {

    public static final String MOD_ID = "pvsound";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PvSound(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();

        context.registerConfig(ModConfig.Type.COMMON, SoundConfig.SPEC);
        ModRegistry.register(modBus);
        PvSoundNetwork.register();

        // Plasmo Voice server addon: initialized when the (dedicated or integrated) voice server starts.
        PlasmoVoiceServer.getAddonsLoader().load(new SoundAddon());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
