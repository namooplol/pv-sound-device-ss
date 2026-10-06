package com.namo.pvsound.client;

import com.namo.pvsound.item.CableRules;
import com.namo.pvsound.network.MixerState;
import com.namo.pvsound.network.SettingsData;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Client-only packet handling (referenced through DistExecutor).
 */
public final class ClientHooks {

    public static void onMixerState(BlockPos pos, boolean open, MixerState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MixerScreen screen && screen.pos().equals(pos)) {
            screen.update(state);
        } else if (open) {
            mc.setScreen(new MixerScreen(pos, state));
        }
    }

    public static void openSettings(SettingsData data) {
        Minecraft.getInstance().setScreen(new SettingsScreen(data));
    }

    public static void onSettingsResult(Component message, boolean ok, String activeCipher) {
        if (Minecraft.getInstance().screen instanceof SettingsScreen screen) {
            screen.onResult(message, ok, activeCipher);
        } else if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(message, true);
        }
    }

    public static void onCableRules(int base, int extension, int maxExtensions) {
        CableRules.baseLength = base;
        CableRules.extensionLength = extension;
        CableRules.maxExtensions = maxExtensions;
    }

    private ClientHooks() {
    }
}
