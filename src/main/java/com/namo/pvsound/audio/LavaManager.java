package com.namo.pvsound.audio;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.SoundConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import su.plo.voice.lavaplayer.libs.com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeSourceOptions;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Android;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.AndroidMusic;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.AndroidVr;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Ios;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.MWeb;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Music;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Tv;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.TvHtml5Simply;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.Web;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.WebEmbedded;
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.clients.skeleton.Client;
import su.plo.voice.lavaplayer.libs.org.apache.http.HttpHost;
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.AuthScope;
import su.plo.voice.lavaplayer.libs.org.apache.http.auth.UsernamePasswordCredentials;
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.BasicCredentialsProvider;
import su.plo.voice.lavaplayer.libs.org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the LavaPlayer {@link AudioPlayerManager}. Players output 48kHz stereo PCM (s16le)
 * so the mixer can run its DSP before encoding per-speaker opus streams.
 */
public final class LavaManager {

    private static final Pattern PROXY = Pattern.compile(
            "^(?:(https?)://)?(?:(\\w+):(\\w*)@)?([a-zA-Z0-9][a-zA-Z0-9-_.]{0,61}):(\\d{1,5})$");

    private static final String DEFAULT_CIPHER_URL = "http://127.0.0.1:8001/";

    private final Path dataDir = FMLPaths.CONFIGDIR.get().resolve("pvsound");
    /** managers replaced by {@link #reload()}; players created from them keep running until shutdown */
    private final List<AudioPlayerManager> retired = new ArrayList<>();
    private volatile AudioPlayerManager manager;
    private volatile String activeCipher = "built-in";

    public AudioPlayer createPlayer() {
        return manager.createPlayer();
    }

    public synchronized void shutdown() {
        if (manager != null) manager.shutdown();
        retired.forEach(AudioPlayerManager::shutdown);
        retired.clear();
    }

    /** Human readable cipher in use: a yt-cipher url or "built-in". */
    public String activeCipher() {
        return activeCipher;
    }

    /**
     * Rebuilds the source managers from the current config (e.g. after changing the yt-cipher server in the
     * settings screen). Tracks already playing keep using the old manager.
     */
    public synchronized void reload() {
        AudioPlayerManager fresh = new DefaultAudioPlayerManager();
        fresh.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
        fresh.setFrameBufferDuration(2_000);
        fresh.setPlayerCleanupThreshold(60_000L);
        registerSources(fresh);

        AudioPlayerManager old = manager;
        manager = fresh;
        if (old != null) retired.add(old);
    }

    /**
     * Resolves a mixer input. URLs are loaded as is, anything else is searched with the configured prefix.
     */
    public CompletableFuture<LoadResult> load(String input) {
        String query = input.trim();
        if (!looksLikeIdentifier(query)) {
            query = SoundConfig.SEARCH_PREFIX.get() + query;
        }

        CompletableFuture<LoadResult> future = new CompletableFuture<>();
        manager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(new LoadResult(List.of(track), null));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    future.completeExceptionally(new IllegalStateException("No matches"));
                    return;
                }
                if (playlist.isSearchResult()) {
                    future.complete(new LoadResult(List.of(playlist.getTracks().get(0)), null));
                    return;
                }

                // honor &index= / selected track in playlist links
                List<AudioTrack> tracks = new ArrayList<>(playlist.getTracks());
                AudioTrack selected = playlist.getSelectedTrack();
                if (selected != null) {
                    int index = tracks.indexOf(selected);
                    if (index > 0) tracks = tracks.subList(index, tracks.size());
                }
                future.complete(new LoadResult(tracks, playlist.getName()));
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new IllegalStateException("No matches"));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private static boolean looksLikeIdentifier(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.matches("^[a-z]+search:.*");
    }

    private void registerSources(AudioPlayerManager target) {
        Consumer<HttpClientBuilder> proxy = proxyConfigurator();
        if (proxy != null) target.setHttpBuilderConfigurator(proxy);

        if (SoundConfig.YOUTUBE_ENABLED.get()) {
            register(target, "youtube", () -> createYoutube(proxy));
        }
        if (SoundConfig.SOUNDCLOUD.get()) register(target, "soundcloud", SoundCloudAudioSourceManager::createDefault);
        if (SoundConfig.BANDCAMP.get()) register(target, "bandcamp", BandcampAudioSourceManager::new);
        if (SoundConfig.VIMEO.get()) register(target, "vimeo", VimeoAudioSourceManager::new);
        if (SoundConfig.TWITCH.get()) register(target, "twitch", TwitchStreamAudioSourceManager::new);
        // http must be last, it accepts any url
        if (SoundConfig.HTTP.get()) register(target, "http", HttpAudioSourceManager::new);
    }

    private YoutubeAudioSourceManager createYoutube(Consumer<HttpClientBuilder> proxy) {
        List<Client> clients = new ArrayList<>();
        for (String name : SoundConfig.YOUTUBE_CLIENTS.get()) {
            Client client = youtubeClient(name);
            if (client == null) {
                PvSound.LOGGER.warn("Unknown YouTube client in config: {}", name);
                continue;
            }
            clients.add(client);
        }
        if (clients.isEmpty()) {
            clients.add(new Music());
            clients.add(new AndroidVr());
            clients.add(new Web());
            clients.add(new Tv());
        }

        YoutubeSourceOptions options = new YoutubeSourceOptions()
                .setAllowSearch(true)
                .setAllowDirectVideoIds(true)
                .setAllowDirectPlaylistIds(true);

        String cipherUrl = resolveRemoteCipher();
        if (cipherUrl != null) {
            String password = SoundConfig.REMOTE_CIPHER_PASSWORD.get().trim();
            options.setRemoteCipher(cipherUrl, password.isEmpty() ? null : password, "pv-sound-system");
            PvSound.LOGGER.info("YouTube remote cipher: {}", cipherUrl);
            activeCipher = cipherUrl;
        } else {
            activeCipher = "built-in";
            PvSound.LOGGER.warn("YouTube remote cipher is not configured; using the built-in cipher. "
                    + "Many videos will fail with \"Must find sig function\" - start backend/start.ps1 (yt-cipher).");
        }

        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(options, clients.toArray(new Client[0]));
        if (proxy != null) youtube.getHttpInterfaceManager().configureBuilder(proxy);

        String poToken = SoundConfig.PO_TOKEN.get().trim();
        if (!poToken.isEmpty()) {
            Web.setPoTokenAndVisitorData(poToken, SoundConfig.PO_VISITOR_DATA.get().trim());
        } else if (SoundConfig.YOUTUBE_OAUTH2.get()) {
            Path tokenFile = dataDir.resolve(".youtube-token");
            String refreshToken = null;
            try {
                if (Files.isRegularFile(tokenFile)) refreshToken = Files.readString(tokenFile).trim();
            } catch (IOException e) {
                PvSound.LOGGER.warn("Failed to read {}", tokenFile, e);
            }
            youtube.useOauth2(refreshToken, false);
            if (refreshToken == null) watchOauthToken(youtube, tokenFile);
        }

        PvSound.LOGGER.info("YouTube clients: {}", clients.stream().map(c -> c.getClass().getSimpleName()).toList());
        return youtube;
    }

    /**
     * Picks the yt-cipher url: explicit config value, or auto-detect a local instance when the value is empty.
     * Also checks the password so a wrong token shows up in the log instead of as failing tracks.
     */
    private static String resolveRemoteCipher() {
        String configured = SoundConfig.REMOTE_CIPHER_URL.get().trim();
        if (configured.equalsIgnoreCase("none")) return null;

        String base = normalize(configured.isEmpty() ? DEFAULT_CIPHER_URL : configured);
        Probe probe = probe(base, SoundConfig.REMOTE_CIPHER_PASSWORD.get().trim());
        switch (probe.status()) {
            case OK -> {
                return base;
            }
            case BAD_TOKEN -> {
                PvSound.LOGGER.error("yt-cipher at {} rejected remoteCipherPassword - set it to the API_TOKEN of the container", base);
                return base;
            }
            default -> {
                if (configured.isEmpty()) return null; // auto mode and nothing is listening
                PvSound.LOGGER.warn("yt-cipher at {} is not reachable right now ({}), using it anyway", base, probe.detail());
                return base;
            }
        }
    }

    public enum ProbeStatus { OK, BAD_TOKEN, UNREACHABLE }

    public record Probe(ProbeStatus status, String detail) {
    }

    public static String normalize(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /**
     * Sends an empty request to yt-cipher: a "player_url missing" reply means the server is up and the token accepted.
     */
    public static Probe probe(String url, String password) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(normalize(url) + "decrypt_signature"))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            if (password != null && !password.isEmpty()) request.header("Authorization", password);

            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.body().contains("API token")) {
                return new Probe(ProbeStatus.BAD_TOKEN, "HTTP " + response.statusCode());
            }
            return new Probe(ProbeStatus.OK, "HTTP " + response.statusCode());
        } catch (Exception e) {
            return new Probe(ProbeStatus.UNREACHABLE, e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * After the device-code login completes, persist the refresh token so the login survives restarts.
     */
    private void watchOauthToken(YoutubeAudioSourceManager youtube, Path tokenFile) {
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < 360; i++) { // up to 30 minutes
                    Thread.sleep(5_000L);
                    String token = youtube.getOauth2RefreshToken();
                    if (token == null) continue;

                    Files.createDirectories(tokenFile.getParent());
                    Files.writeString(tokenFile, token);
                    PvSound.LOGGER.info("YouTube OAuth2 refresh token saved to {}", tokenFile);
                    return;
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                PvSound.LOGGER.error("Failed to save YouTube refresh token", e);
            }
        }, "pvsound-youtube-token");
        thread.setDaemon(true);
        thread.start();
    }

    private static Client youtubeClient(String name) {
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "MUSIC" -> new Music();
            case "WEB" -> new Web();
            case "MWEB" -> new MWeb();
            case "WEBEMBEDDED" -> new WebEmbedded();
            case "ANDROID" -> new Android();
            case "ANDROID_VR" -> new AndroidVr();
            case "ANDROID_MUSIC" -> new AndroidMusic();
            case "IOS" -> new Ios();
            case "TV" -> new Tv();
            case "TVHTML5_SIMPLY" -> new TvHtml5Simply();
            default -> null;
        };
    }

    private static Consumer<HttpClientBuilder> proxyConfigurator() {
        String raw = SoundConfig.HTTP_PROXY.get().trim();
        if (raw.isEmpty()) return null;

        Matcher m = PROXY.matcher(raw);
        if (!m.matches()) {
            PvSound.LOGGER.error("Failed to parse httpProxy: {}", raw);
            return null;
        }

        HttpHost host = new HttpHost(m.group(4), Integer.parseInt(m.group(5)), m.group(1));
        BasicCredentialsProvider credentials = null;
        if (m.group(2) != null && !m.group(2).isBlank()) {
            credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(m.group(2), m.group(3)));
        }

        BasicCredentialsProvider finalCredentials = credentials;
        return builder -> {
            builder.setProxy(host);
            if (finalCredentials != null) builder.setDefaultCredentialsProvider(finalCredentials);
        };
    }

    private static void register(AudioPlayerManager target, String name, Supplier<AudioSourceManager> factory) {
        try {
            target.registerSourceManager(factory.get());
        } catch (Throwable t) {
            PvSound.LOGGER.error("Failed to register {} source", name, t);
        }
    }

    public record LoadResult(List<AudioTrack> tracks, String playlistName) {
    }
}
