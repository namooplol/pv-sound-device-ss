package com.namo.pvsound;

import com.namo.pvsound.audio.AudioEngine;
import com.namo.pvsound.audio.LavaManager;
import com.namo.pvsound.audio.MicrophoneRouter;
import com.namo.pvsound.item.CableRules;
import org.jetbrains.annotations.Nullable;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.addon.annotation.Dependency;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;

@Addon(
        id = "pv-addon-sound-system",
        name = "PV Sound System",
        scope = AddonLoaderScope.SERVER,
        version = "1.0.0",
        authors = {"namo"},
        dependencies = {@Dependency(id = "pv-addon-lavaplayer-lib")}
)
public final class SoundAddon implements AddonInitializer {

    public static final String SOURCE_LINE = "speakers";

    private static volatile @Nullable SoundAddon instance;

    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;

    private ServerSourceLine sourceLine;
    private LavaManager lava;
    private AudioEngine engine;
    private MicrophoneRouter microphones;
    /** bumped when settings change so mixers re-apply ranges to their speakers */
    private volatile int settingsVersion;

    /**
     * @return running addon, or null while the voice server is not started.
     */
    public static @Nullable SoundAddon get() {
        return instance;
    }

    @Override
    public void onAddonInitialize() {
        voiceServer.getSourceLineManager().unregister(SOURCE_LINE);
        sourceLine = voiceServer.getSourceLineManager()
                .createBuilder(
                        this,
                        SOURCE_LINE,
                        "pv.activation.speakers",
                        "plasmovoice:textures/icons/speaker_disc.png",
                        12
                )
                .setDefaultVolume(SoundConfig.SOURCE_LINE_DEFAULT_VOLUME.get())
                .build();

        lava = new LavaManager();
        lava.reload();
        CableRules.loadFromConfig();

        engine = new AudioEngine();
        engine.start();

        microphones = new MicrophoneRouter(voiceServer);
        voiceServer.getEventBus().register(this, microphones);

        instance = this;
        PvSound.LOGGER.info("PV Sound System addon initialized");
    }

    @Override
    public void onAddonShutdown() {
        instance = null;

        if (microphones != null) {
            voiceServer.getEventBus().unregister(this, microphones);
            microphones.shutdown();
        }
        if (engine != null) engine.shutdown();
        if (lava != null) lava.shutdown();
        voiceServer.getSourceLineManager().unregister(SOURCE_LINE);
    }

    public PlasmoVoiceServer voiceServer() {
        return voiceServer;
    }

    public ServerSourceLine sourceLine() {
        return sourceLine;
    }

    public LavaManager lava() {
        return lava;
    }

    public AudioEngine engine() {
        return engine;
    }

    public int settingsVersion() {
        return settingsVersion;
    }

    public void bumpSettingsVersion() {
        settingsVersion++;
    }

    public MicrophoneRouter microphones() {
        return microphones;
    }
}
