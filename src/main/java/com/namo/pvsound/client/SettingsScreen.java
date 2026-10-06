package com.namo.pvsound.client;

import com.namo.pvsound.network.PvSoundNetwork;
import com.namo.pvsound.network.SettingsData;
import com.namo.pvsound.network.SettingsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.IntFunction;

/**
 * /pvsound setting: yt-cipher server, search source, cable and speaker limits.
 */
public class SettingsScreen extends Screen {

    private static final int W = 320;
    private static final int H = 262;

    private static final int BG = 0xF0141820;
    private static final int PANEL = 0xFF1E2430;
    private static final int EDGE = 0xFF3A4458;

    private static final String LOCAL_CIPHER = "http://127.0.0.1:8001/";
    private static final String PUBLIC_CIPHER = "https://cipher.kikkia.dev/";
    private static final List<String> PREFIXES = List.of("ytsearch:", "ytmsearch:", "scsearch:");

    private SettingsData data;
    private Component result = Component.empty();
    private boolean resultOk = true;
    private String activeCipher;

    private EditBox urlBox;
    private EditBox passwordBox;
    private EditBox testBox;
    private Button prefixButton;
    private String prefix;

    private IntSlider cableLength, extensionLength, maxExtensions, maxSpeakers, speakerRange, subRange, cone, bitrate;

    public SettingsScreen(SettingsData data) {
        super(Component.translatable("pvsound.settings.title"));
        this.data = data;
        this.prefix = data.searchPrefix();
        this.activeCipher = data.activeCipher();
    }

    public void onResult(Component message, boolean ok, String activeCipher) {
        this.result = message;
        this.resultOk = ok;
        this.activeCipher = activeCipher;
    }

    @Override
    protected void init() {
        int left = (width - W) / 2;
        int top = (height - H) / 2;
        int x = left + 8;

        // --- YouTube / yt-cipher
        urlBox = addRenderableWidget(new EditBox(font, x + 70, top + 30, 170, 16, Component.empty()));
        urlBox.setMaxLength(500);
        urlBox.setValue(urlBox.getValue().isEmpty() ? data.cipherUrl() : urlBox.getValue());
        urlBox.setHint(Component.translatable("pvsound.settings.url.hint").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.test"), b ->
                send(SettingsNetwork.Action.TEST_CIPHER, urlBox.getValue(), passwordBox.getValue()))
                .bounds(x + 244, top + 28, 60, 20).build());

        passwordBox = addRenderableWidget(new EditBox(font, x + 70, top + 52, 170, 16, Component.empty()));
        passwordBox.setMaxLength(200);
        passwordBox.setValue(data.cipherPassword());
        passwordBox.setFormatter((text, start) -> net.minecraft.util.FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        passwordBox.setHint(Component.translatable("pvsound.settings.password.hint").withStyle(ChatFormatting.DARK_GRAY));

        int py = top + 74;
        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.preset.auto"), b -> urlBox.setValue(""))
                .bounds(x, py, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.preset.local"), b -> urlBox.setValue(LOCAL_CIPHER))
                .bounds(x + 76, py, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.preset.public"), b -> urlBox.setValue(PUBLIC_CIPHER))
                .bounds(x + 152, py, 74, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.preset.off"), b -> urlBox.setValue("none"))
                .bounds(x + 228, py, 76, 20).build());

        int sy = top + 98;
        prefixButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            prefix = PREFIXES.get((PREFIXES.indexOf(prefix) + 1) % PREFIXES.size());
            updatePrefixLabel();
        }).bounds(x, sy, 110, 20).build());
        updatePrefixLabel();
        testBox = addRenderableWidget(new EditBox(font, x + 114, sy + 2, 126, 16, Component.empty()));
        testBox.setMaxLength(500);
        testBox.setHint(Component.translatable("pvsound.settings.test_track.hint").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(Button.builder(Component.literal("▶ ").append(Component.translatable("pvsound.settings.test")), b ->
                send(SettingsNetwork.Action.TEST_TRACK, testBox.getValue(), ""))
                .bounds(x + 244, sy, 60, 20).build());

        // --- cable
        int cy = top + 146;
        cableLength = slider(x, cy, "cable_length", 2, 256, data.cableLength(), v -> v + "");
        extensionLength = slider(x + 154, cy, "extension_length", 1, 128, data.extensionLength(), v -> "+" + v);
        maxExtensions = slider(x, cy + 22, "max_extensions", 0, 32, data.maxExtensions(), v -> v + "");
        maxSpeakers = slider(x + 154, cy + 22, "max_speakers", 1, 64, data.maxSpeakers(), v -> v + "");

        // --- audio
        int ay = top + 202;
        speakerRange = slider(x, ay, "speaker_range", 4, 128, data.speakerRange(), v -> v + "");
        subRange = slider(x + 154, ay, "sub_range", 4, 128, data.subwooferRange(), v -> v + "");
        cone = slider(x, ay + 22, "cone", 0, 355, data.coneAngle(), v -> v == 0 ? "360°" : v + "°");
        bitrate = slider(x + 154, ay + 22, "bitrate", 32, 256, data.bitrateKbps(), v -> v + "k");

        addRenderableWidget(Button.builder(Component.translatable("pvsound.settings.save").withStyle(ChatFormatting.GREEN), b -> save())
                .bounds(left + W - 128, top + H - 6, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + W - 66, top + H - 6, 58, 20).build());
    }

    private IntSlider slider(int x, int y, String key, int min, int max, int value, IntFunction<String> format) {
        return addRenderableWidget(new IntSlider(x, y, 150, key, min, max, value, format));
    }

    private void updatePrefixLabel() {
        String key = switch (prefix) {
            case "ytmsearch:" -> "pvsound.settings.search.ytmusic";
            case "scsearch:" -> "pvsound.settings.search.soundcloud";
            default -> "pvsound.settings.search.youtube";
        };
        prefixButton.setMessage(Component.translatable("pvsound.settings.search", Component.translatable(key)));
    }

    private void send(SettingsNetwork.Action action, String a, String b) {
        result = Component.translatable("pvsound.settings.testing").withStyle(ChatFormatting.GRAY);
        resultOk = true;
        PvSoundNetwork.CHANNEL.sendToServer(new SettingsNetwork.SettingsActionC2S(action, a, b));
    }

    private void save() {
        data = new SettingsData(
                urlBox.getValue().trim(), passwordBox.getValue().trim(), prefix,
                cableLength.intValue(), extensionLength.intValue(), maxExtensions.intValue(), maxSpeakers.intValue(),
                speakerRange.intValue(), subRange.intValue(), cone.intValue(), bitrate.intValue(), activeCipher);
        result = Component.translatable("pvsound.settings.saving").withStyle(ChatFormatting.GRAY);
        PvSoundNetwork.CHANNEL.sendToServer(new SettingsNetwork.SaveSettingsC2S(data));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        urlBox.tick();
        passwordBox.tick();
        testBox.tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = (width - W) / 2;
        int top = (height - H) / 2;
        int x = left + 8;

        g.fill(left - 1, top - 1, left + W + 1, top + H + 21, EDGE);
        g.fill(left, top, left + W, top + H + 20, BG);
        g.fill(left, top, left + W, top + 14, PANEL);
        g.drawString(font, Component.literal("⚙ ").withStyle(ChatFormatting.GOLD).append(title), left + 6, top + 3, 0xFFFFFF, false);

        section(g, x, top + 18, "pvsound.settings.section.youtube");
        g.drawString(font, Component.translatable("pvsound.settings.url"), x, top + 34, 0xC8D0E0, false);
        g.drawString(font, Component.translatable("pvsound.settings.password"), x, top + 56, 0xC8D0E0, false);
        g.drawString(font, Component.translatable("pvsound.settings.active", activeCipher).withStyle(ChatFormatting.DARK_AQUA),
                x, top + 122, 0xFFFFFF, false);

        section(g, x, top + 134, "pvsound.settings.section.cable");
        section(g, x, top + 190, "pvsound.settings.section.audio");

        if (result != null && !result.getString().isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(result.getString(), W - 140), x, top + H, resultOk ? 0x9AE6B4 : 0xFF6060, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void section(GuiGraphics g, int x, int y, String key) {
        g.drawString(font, Component.translatable(key).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), x, y, 0xFFFFFF, false);
        g.fill(x, y + 10, x + W - 16, y + 11, EDGE);
    }

    /** Integer slider with a translated label. */
    private static final class IntSlider extends AbstractSliderButton {

        private final String key;
        private final int min;
        private final int max;
        private final IntFunction<String> format;

        IntSlider(int x, int y, int w, String key, int min, int max, int value, IntFunction<String> format) {
            super(x, y, w, 20, Component.empty(), (Mth.clamp(value, min, max) - min) / (double) (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.format = format;
            updateMessage();
        }

        int intValue() {
            return (int) Math.round(min + value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("pvsound.settings." + key)
                    .append(": ")
                    .append(Component.literal(format.apply(intValue())).withStyle(ChatFormatting.YELLOW)));
        }

        @Override
        protected void applyValue() {
        }
    }
}
