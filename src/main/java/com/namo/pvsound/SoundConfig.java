package com.namo.pvsound;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Common config (config/pvsound-common.toml). Loaded at startup so LavaPlayer can be
 * configured before Plasmo Voice initializes addons.
 */
public final class SoundConfig {

    public static final ForgeConfigSpec SPEC;

    // youtube
    public static final ForgeConfigSpec.BooleanValue YOUTUBE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> YOUTUBE_CLIENTS;
    public static final ForgeConfigSpec.ConfigValue<String> REMOTE_CIPHER_URL;
    public static final ForgeConfigSpec.ConfigValue<String> REMOTE_CIPHER_PASSWORD;
    public static final ForgeConfigSpec.BooleanValue YOUTUBE_OAUTH2;
    public static final ForgeConfigSpec.ConfigValue<String> PO_TOKEN;
    public static final ForgeConfigSpec.ConfigValue<String> PO_VISITOR_DATA;
    public static final ForgeConfigSpec.ConfigValue<String> SEARCH_PREFIX;

    // other sources
    public static final ForgeConfigSpec.BooleanValue SOUNDCLOUD;
    public static final ForgeConfigSpec.BooleanValue BANDCAMP;
    public static final ForgeConfigSpec.BooleanValue VIMEO;
    public static final ForgeConfigSpec.BooleanValue TWITCH;
    public static final ForgeConfigSpec.BooleanValue HTTP;
    public static final ForgeConfigSpec.ConfigValue<String> HTTP_PROXY;

    // audio
    public static final ForgeConfigSpec.IntValue OPUS_BITRATE;
    public static final ForgeConfigSpec.IntValue SPEAKER_RANGE;
    public static final ForgeConfigSpec.IntValue SUBWOOFER_RANGE;
    public static final ForgeConfigSpec.IntValue SPEAKER_CONE_ANGLE;
    public static final ForgeConfigSpec.DoubleValue SOURCE_LINE_DEFAULT_VOLUME;

    // gameplay
    public static final ForgeConfigSpec.IntValue CABLE_MAX_LENGTH;
    public static final ForgeConfigSpec.IntValue CABLE_EXTENSION_LENGTH;
    public static final ForgeConfigSpec.IntValue MAX_CABLE_EXTENSIONS;
    public static final ForgeConfigSpec.IntValue MAX_SPEAKERS_PER_MIXER;
    public static final ForgeConfigSpec.IntValue MAX_QUEUE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("YouTube source (dev.lavalink.youtube). See backend/README.md for the remote cipher setup.").push("youtube");
        YOUTUBE_ENABLED = b.define("enabled", true);
        YOUTUBE_CLIENTS = b.comment("Clients tried in order: MUSIC, WEB, MWEB, WEBEMBEDDED, ANDROID, ANDROID_VR, ANDROID_MUSIC, IOS, TV, TVHTML5_SIMPLY")
                .defineListAllowEmpty("clients",
                        List.of("MUSIC", "ANDROID_VR", "WEB", "WEBEMBEDDED", "TV", "TVHTML5_SIMPLY"),
                        o -> o instanceof String);
        REMOTE_CIPHER_URL = b.comment("yt-cipher server url. Empty = auto: use http://127.0.0.1:8001/ if a yt-cipher is running there, otherwise the built-in cipher. \"none\" = never use a remote cipher")
                .define("remoteCipherUrl", "");
        REMOTE_CIPHER_PASSWORD = b.comment("API_TOKEN of the yt-cipher server (empty = none)")
                .define("remoteCipherPassword", "");
        YOUTUBE_OAUTH2 = b.comment("Use OAuth2 (TV client). The login code is printed to the server log on first use; the refresh token is saved to config/pvsound/.youtube-token")
                .define("useOauth2", false);
        PO_TOKEN = b.comment("Optional poToken for WEB client").define("poToken", "");
        PO_VISITOR_DATA = b.define("poVisitorData", "");
        SEARCH_PREFIX = b.comment("Prefix used when the mixer input is not a URL: ytsearch:, ytmsearch:, scsearch:")
                .define("searchPrefix", "ytsearch:");
        b.pop();

        b.push("sources");
        SOUNDCLOUD = b.define("soundcloud", true);
        BANDCAMP = b.define("bandcamp", true);
        VIMEO = b.define("vimeo", true);
        TWITCH = b.define("twitch", true);
        HTTP = b.comment("Direct http(s) links to audio files / radio streams").define("http", true);
        HTTP_PROXY = b.comment("Optional proxy for all sources: [http://][user:pass@]host:port").define("httpProxy", "");
        b.pop();

        b.push("audio");
        OPUS_BITRATE = b.comment("Opus bitrate per speaker channel (music mode)").defineInRange("opusBitrate", 96_000, 16_000, 256_000);
        SPEAKER_RANGE = b.comment("Audible distance of a speaker in blocks").defineInRange("speakerRange", 32, 4, 256);
        SUBWOOFER_RANGE = b.defineInRange("subwooferRange", 48, 4, 256);
        SPEAKER_CONE_ANGLE = b.comment("Directional cone of speakers in degrees (sound is quieter behind the speaker). 0 = omnidirectional")
                .defineInRange("speakerConeAngle", 270, 0, 359);
        SOURCE_LINE_DEFAULT_VOLUME = b.defineInRange("sourceLineDefaultVolume", 0.8, 0.0, 2.0);
        b.pop();

        b.push("gameplay");
        CABLE_MAX_LENGTH = b.comment("Length of a new speaker cable in blocks (measured along the clips)").defineInRange("cableMaxLength", 24, 2, 256);
        CABLE_EXTENSION_LENGTH = b.comment("Blocks added by each Cable Extension").defineInRange("cableExtensionLength", 16, 1, 256);
        MAX_CABLE_EXTENSIONS = b.comment("How many extensions one cable can take").defineInRange("maxCableExtensions", 8, 0, 64);
        MAX_SPEAKERS_PER_MIXER = b.defineInRange("maxSpeakersPerMixer", 16, 1, 128);
        MAX_QUEUE = b.defineInRange("maxQueue", 100, 1, 1000);
        b.pop();

        SPEC = b.build();
    }

    private SoundConfig() {
    }
}
