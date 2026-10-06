package com.namo.pvsound;

import com.namo.pvsound.network.SettingsNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PvSound.MOD_ID)
public final class ServerEvents {

    private static int ticks;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 5 != 0) return;

        SoundAddon addon = SoundAddon.get();
        if (addon != null) addon.microphones().refresh(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SettingsNetwork.send(player, SettingsHandler.rules());
        }
    }

    private ServerEvents() {
    }
}
