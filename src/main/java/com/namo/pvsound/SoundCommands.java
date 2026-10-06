package com.namo.pvsound;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import java.util.concurrent.TimeUnit;

/**
 * /pvsound test &lt;url or search&gt; — checks that LavaPlayer (and the YouTube backend) can resolve a track.
 * /pvsound setting — opens the settings screen (operators / singleplayer host).
 */
@Mod.EventBusSubscriber(modid = PvSound.MOD_ID)
public final class SoundCommands {

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pvsound")
                .then(Commands.literal("test")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("query", StringArgumentType.greedyString())
                                .executes(ctx -> test(ctx.getSource(), StringArgumentType.getString(ctx, "query")))))
                .then(Commands.literal("setting").executes(ctx -> openSettings(ctx.getSource())))
                .then(Commands.literal("settings").executes(ctx -> openSettings(ctx.getSource()))));
    }

    private static int openSettings(CommandSourceStack source) throws CommandSyntaxException {
        SettingsHandler.open(source.getPlayerOrException());
        return 1;
    }

    public static int test(CommandSourceStack source, String query) {
        SoundAddon addon = SoundAddon.get();
        if (addon == null) {
            source.sendFailure(Component.translatable("pvsound.mixer.not_ready"));
            return 0;
        }

        source.sendSystemMessage(Component.literal("Loading " + query + " ...").withStyle(ChatFormatting.GRAY));
        addon.lava().load(query).whenComplete((result, error) -> source.getServer().execute(() -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                PvSound.LOGGER.warn("pvsound test failed for {}", query, cause);
                source.sendFailure(Component.literal("Failed: " + cause.getMessage()));
                return;
            }
            AudioTrack first = result.tracks().get(0);
            source.sendSystemMessage(Component.literal("Found: " + first.getInfo().title + " — " + first.getInfo().author
                    + " (" + first.getDuration() / 1000 + "s, " + result.tracks().size() + " track(s), source "
                    + first.getSourceManager().getSourceName() + ")").withStyle(ChatFormatting.GREEN));
            decodeProbe(source, addon, first);
        }));
        return 1;
    }

    /** Actually streams ~3 seconds of audio: catches cipher / stream URL failures that loading alone doesn't. */
    private static void decodeProbe(CommandSourceStack source, SoundAddon addon, AudioTrack track) {
        Thread thread = new Thread(() -> {
            AudioPlayer player = addon.lava().createPlayer();
            String[] failure = {null};
            player.addListener(new AudioEventAdapter() {
                @Override
                public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) {
                    failure[0] = e.getMessage() + (e.getCause() != null ? " (" + e.getCause().getMessage() + ")" : "");
                }
            });
            player.playTrack(track.makeClone());

            int frames = 0;
            long deadline = System.currentTimeMillis() + 15_000;
            try {
                while (frames < 150 && failure[0] == null && System.currentTimeMillis() < deadline) {
                    AudioFrame frame = player.provide(20, TimeUnit.MILLISECONDS);
                    if (frame != null) frames++;
                }
            } catch (Exception e) {
                failure[0] = e.toString();
            } finally {
                player.destroy();
            }

            int decoded = frames;
            String error = failure[0];
            source.getServer().execute(() -> {
                if (error != null) {
                    source.sendFailure(Component.literal("Playback failed: " + error));
                } else if (decoded == 0) {
                    source.sendFailure(Component.literal("Playback failed: no audio within 15s"));
                } else {
                    source.sendSystemMessage(Component.literal("Playback OK: decoded " + decoded * 20 + "ms of audio")
                            .withStyle(ChatFormatting.GREEN));
                }
            });
        }, "pvsound-test");
        thread.setDaemon(true);
        thread.start();
    }

    private SoundCommands() {
    }
}
