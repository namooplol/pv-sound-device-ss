package com.namo.pvsound;

import com.namo.pvsound.audio.LavaManager;
import com.namo.pvsound.item.CableRules;
import com.namo.pvsound.network.SettingsData;
import com.namo.pvsound.network.SettingsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.Set;

/**
 * Server side of the /pvsound setting screen. Only operators (or the singleplayer host) may change settings.
 */
public final class SettingsHandler {

    public static final Set<String> SEARCH_PREFIXES = Set.of("ytsearch:", "ytmsearch:", "scsearch:");

    public static boolean canEdit(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return player.hasPermissions(2) || (server != null && server.isSingleplayerOwner(player.getGameProfile()));
    }

    public static void open(ServerPlayer player) {
        if (!canEdit(player)) {
            player.sendSystemMessage(Component.translatable("pvsound.settings.no_permission").withStyle(ChatFormatting.RED));
            return;
        }
        SettingsNetwork.send(player, new SettingsNetwork.OpenSettingsS2C(current()));
    }

    public static SettingsData current() {
        SoundAddon addon = SoundAddon.get();
        return new SettingsData(
                SoundConfig.REMOTE_CIPHER_URL.get(),
                SoundConfig.REMOTE_CIPHER_PASSWORD.get(),
                SoundConfig.SEARCH_PREFIX.get(),
                SoundConfig.CABLE_MAX_LENGTH.get(),
                SoundConfig.CABLE_EXTENSION_LENGTH.get(),
                SoundConfig.MAX_CABLE_EXTENSIONS.get(),
                SoundConfig.MAX_SPEAKERS_PER_MIXER.get(),
                SoundConfig.SPEAKER_RANGE.get(),
                SoundConfig.SUBWOOFER_RANGE.get(),
                SoundConfig.SPEAKER_CONE_ANGLE.get(),
                SoundConfig.OPUS_BITRATE.get() / 1000,
                addon == null ? "-" : addon.lava().activeCipher()
        );
    }

    public static void save(ServerPlayer player, SettingsData data) {
        if (player == null || !canEdit(player)) return;

        String url = data.cipherUrl().trim();
        boolean youtubeChanged = !url.equals(SoundConfig.REMOTE_CIPHER_URL.get())
                || !data.cipherPassword().equals(SoundConfig.REMOTE_CIPHER_PASSWORD.get());

        SoundConfig.REMOTE_CIPHER_URL.set(url);
        SoundConfig.REMOTE_CIPHER_PASSWORD.set(data.cipherPassword().trim());
        if (SEARCH_PREFIXES.contains(data.searchPrefix())) SoundConfig.SEARCH_PREFIX.set(data.searchPrefix());
        SoundConfig.CABLE_MAX_LENGTH.set(Mth.clamp(data.cableLength(), 2, 256));
        SoundConfig.CABLE_EXTENSION_LENGTH.set(Mth.clamp(data.extensionLength(), 1, 256));
        SoundConfig.MAX_CABLE_EXTENSIONS.set(Mth.clamp(data.maxExtensions(), 0, 64));
        SoundConfig.MAX_SPEAKERS_PER_MIXER.set(Mth.clamp(data.maxSpeakers(), 1, 128));
        SoundConfig.SPEAKER_RANGE.set(Mth.clamp(data.speakerRange(), 4, 256));
        SoundConfig.SUBWOOFER_RANGE.set(Mth.clamp(data.subwooferRange(), 4, 256));
        SoundConfig.SPEAKER_CONE_ANGLE.set(Mth.clamp(data.coneAngle(), 0, 359));
        SoundConfig.OPUS_BITRATE.set(Mth.clamp(data.bitrateKbps(), 16, 256) * 1000);
        SoundConfig.SPEC.save();

        CableRules.loadFromConfig();
        SettingsNetwork.broadcast(rules());

        SoundAddon addon = SoundAddon.get();
        if (addon != null) addon.bumpSettingsVersion();
        PvSound.LOGGER.info("{} changed PV Sound System settings", player.getGameProfile().getName());

        if (addon == null || !youtubeChanged) {
            reply(player, "pvsound.settings.saved", true);
            return;
        }

        // rebuilding the YouTube source probes the cipher server, keep that off the server thread
        runAsync(() -> {
            addon.lava().reload();
            String active = addon.lava().activeCipher();
            player.server.execute(() -> SettingsNetwork.send(player, new SettingsNetwork.SettingsResultS2C(
                    Component.translatable("pvsound.settings.saved_reloaded", active), true, active)));
        });
    }

    public static void action(ServerPlayer player, SettingsNetwork.Action action, String a, String b) {
        if (player == null || !canEdit(player)) return;

        switch (action) {
            case TEST_CIPHER -> runAsync(() -> {
                String url = a.isBlank() ? "http://127.0.0.1:8001/" : a;
                LavaManager.Probe probe = LavaManager.probe(url, b.trim());
                Component message = switch (probe.status()) {
                    case OK -> Component.literal("✔ " + LavaManager.normalize(url) + " (" + probe.detail() + ")");
                    case BAD_TOKEN -> Component.literal("✘ ").append(Component.translatable("pvsound.settings.bad_token"));
                    case UNREACHABLE -> Component.literal("✘ " + probe.detail());
                };
                player.server.execute(() -> SettingsNetwork.send(player, new SettingsNetwork.SettingsResultS2C(
                        message, probe.status() == LavaManager.ProbeStatus.OK, activeCipher())));
            });
            case TEST_TRACK -> {
                if (a.isBlank()) return;
                SoundCommands.test(player.createCommandSourceStack(), a.trim());
                reply(player, "pvsound.settings.test_in_chat", true);
            }
        }
    }

    public static SettingsNetwork.CableRulesS2C rules() {
        return new SettingsNetwork.CableRulesS2C(
                SoundConfig.CABLE_MAX_LENGTH.get(),
                SoundConfig.CABLE_EXTENSION_LENGTH.get(),
                SoundConfig.MAX_CABLE_EXTENSIONS.get());
    }

    private static String activeCipher() {
        SoundAddon addon = SoundAddon.get();
        return addon == null ? "-" : addon.lava().activeCipher();
    }

    private static void reply(ServerPlayer player, String key, boolean ok) {
        SettingsNetwork.send(player, new SettingsNetwork.SettingsResultS2C(
                Component.translatable(key), ok, activeCipher()));
    }

    private static void runAsync(Runnable task) {
        Thread thread = new Thread(task, "pvsound-settings");
        thread.setDaemon(true);
        thread.start();
    }

    private SettingsHandler() {
    }
}
