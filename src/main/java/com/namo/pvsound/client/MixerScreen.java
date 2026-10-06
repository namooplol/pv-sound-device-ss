package com.namo.pvsound.client;

import com.namo.pvsound.audio.MixerParam;
import com.namo.pvsound.audio.MixerSession;
import com.namo.pvsound.audio.SpeakerChannel;
import com.namo.pvsound.network.MixerState;
import com.namo.pvsound.network.PvSoundNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * DJ console screen: source input, transport, knobs, VU meters and the queue.
 */
public class MixerScreen extends Screen {

    private static final int W = 320;
    private static final int H = 232;

    private static final int BG = 0xF0141820;
    private static final int PANEL = 0xFF1E2430;
    private static final int EDGE = 0xFF3A4458;
    private static final int ACCENT = 0xFFFFB52E;

    private final BlockPos pos;
    private MixerState state;

    private EditBox input;
    private Button pauseButton;
    private Button loopButton;
    private final List<Knob> knobs = new ArrayList<>();

    private float peakL, peakR, peakS;
    private float shownL, shownR, shownS;

    public MixerScreen(BlockPos pos, MixerState state) {
        super(Component.translatable("block.pvsound.mixer"));
        this.pos = pos;
        this.state = state;
    }

    public BlockPos pos() {
        return pos;
    }

    public void update(MixerState state) {
        this.state = state;
        for (Knob knob : knobs) knob.sync(state.params()[knob.param.ordinal()]);
        refreshButtons();
    }

    @Override
    protected void init() {
        int left = (width - W) / 2;
        int top = (height - H) / 2;

        String previous = input != null ? input.getValue() : "";
        input = new EditBox(font, left + 8, top + 20, 196, 16, Component.translatable("pvsound.gui.input"));
        input.setMaxLength(1000);
        input.setHint(Component.translatable("pvsound.gui.input.hint").withStyle(ChatFormatting.DARK_GRAY));
        input.setValue(previous);
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("▶ ").append(Component.translatable("pvsound.gui.play")),
                b -> submit(PvSoundNetwork.Action.PLAY_NOW)).bounds(left + 208, top + 18, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ ").append(Component.translatable("pvsound.gui.queue")),
                b -> submit(PvSoundNetwork.Action.ENQUEUE)).bounds(left + 262, top + 18, 50, 20).build());

        // transport row
        int ty = top + 84;
        pauseButton = addRenderableWidget(Button.builder(Component.literal("⏸"),
                b -> send(PvSoundNetwork.Action.PAUSE, "")).bounds(left + 8, ty, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⏭"),
                b -> send(PvSoundNetwork.Action.SKIP, "")).bounds(left + 40, ty, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⏹"),
                b -> send(PvSoundNetwork.Action.STOP, "")).bounds(left + 72, ty, 30, 20).build());
        loopButton = addRenderableWidget(Button.builder(Component.empty(),
                b -> send(PvSoundNetwork.Action.LOOP, "")).bounds(left + 104, ty, 86, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("pvsound.gui.clear"),
                b -> send(PvSoundNetwork.Action.CLEAR_QUEUE, "")).bounds(left + 192, ty, 60, 20).build());

        // knobs: 2 rows x 5
        knobs.clear();
        MixerParam[] params = MixerParam.values();
        int kw = 59;
        for (int i = 0; i < params.length; i++) {
            int col = i % 5;
            int row = i / 5;
            Knob knob = new Knob(left + 8 + col * (kw + 2), top + 112 + row * 22, kw, params[i],
                    state.params()[params[i].ordinal()]);
            knobs.add(addRenderableWidget(knob));
        }

        refreshButtons();
    }

    private void refreshButtons() {
        if (pauseButton == null) return;
        pauseButton.setMessage(Component.literal(state.paused() ? "▶" : "⏸"));
        String loop = switch (MixerSession.LoopMode.values()[Mth.clamp(state.loopMode(), 0, 2)]) {
            case OFF -> "pvsound.gui.loop.off";
            case TRACK -> "pvsound.gui.loop.track";
            case QUEUE -> "pvsound.gui.loop.queue";
        };
        loopButton.setMessage(Component.literal("🔁 ").append(Component.translatable(loop)));
    }

    private void submit(PvSoundNetwork.Action action) {
        String value = input.getValue().trim();
        if (value.isEmpty()) return;
        send(action, value);
        input.setValue("");
    }

    private void send(PvSoundNetwork.Action action, String arg) {
        PvSoundNetwork.CHANNEL.sendToServer(new PvSoundNetwork.MixerActionC2S(pos, action, arg));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (input.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            submit(Screen.hasShiftDown() ? PvSoundNetwork.Action.ENQUEUE : PvSoundNetwork.Action.PLAY_NOW);
            return true;
        }
        // don't close the screen while typing the inventory key
        if (input.isFocused() && key != GLFW.GLFW_KEY_ESCAPE) {
            return input.keyPressed(key, scan, modifiers) || input.canConsumeInput();
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void removed() {
        send(PvSoundNetwork.Action.CLOSE, "");
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        input.tick();
        // meters with peak hold
        shownL = approach(shownL, state.levelLeft());
        shownR = approach(shownR, state.levelRight());
        shownS = approach(shownS, state.levelSub());
        peakL = Math.max(peakL - 0.02f, shownL);
        peakR = Math.max(peakR - 0.02f, shownR);
        peakS = Math.max(peakS - 0.02f, shownS);
    }

    private static float approach(float current, float target) {
        return target > current ? current + (target - current) * 0.7f : current + (target - current) * 0.25f;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int left = (width - W) / 2;
        int top = (height - H) / 2;

        // chassis
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, EDGE);
        g.fill(left, top, left + W, top + H, BG);
        g.fill(left, top, left + W, top + 14, PANEL);
        g.drawString(font, Component.literal("◆ ").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("block.pvsound.mixer").withStyle(ChatFormatting.WHITE)), left + 6, top + 3, 0xFFFFFF, false);
        drawSpeakerCounts(g, left + W - 6, top + 3);

        // now playing
        g.fill(left + 8, top + 42, left + W - 8, top + 80, PANEL);
        if (state.playing()) {
            g.drawString(font, ellipsize(state.title(), W - 100), left + 12, top + 46, 0xFFFFFF, false);
            g.drawString(font, ellipsize(state.author(), W - 100), left + 12, top + 56, 0x9AA4B8, false);
            drawProgress(g, left + 12, top + 68, W - 24 - 70);
        } else {
            g.drawString(font, Component.translatable("pvsound.gui.idle").withStyle(ChatFormatting.DARK_GRAY), left + 12, top + 50, 0xFFFFFF, false);
        }
        drawMeters(g, left + W - 76, top + 46);

        // status line
        String status = state.status();
        if (!status.isEmpty()) {
            int color = status.startsWith("!") ? 0xFF6060 : 0x9AE6B4;
            String text = status.startsWith("!") || status.startsWith("…") ? status.substring(1) : status;
            if (status.startsWith("…")) text = "⏳ " + text;
            g.drawString(font, ellipsize(text, W - 16), left + 8, top + 159, color, false);
        }

        // queue
        int qy = top + 170;
        g.drawString(font, Component.translatable("pvsound.gui.up_next", state.queueSize()).withStyle(ChatFormatting.GOLD), left + 8, qy, 0xFFFFFF, false);
        List<String> queue = state.queue();
        for (int i = 0; i < Math.min(queue.size(), 5); i++) {
            g.drawString(font, ellipsize((i + 1) + ". " + queue.get(i), W - 16), left + 10, qy + 11 + i * 10, 0xC8D0E0, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawSpeakerCounts(GuiGraphics g, int right, int y) {
        int[] counts = state.channelCounts();
        StringBuilder text = new StringBuilder();
        Component line = Component.empty();
        String[] labels = {"L", "R", "M", "SUB"};
        for (SpeakerChannel ch : SpeakerChannel.values()) {
            int c = ch.ordinal() < counts.length ? counts[ch.ordinal()] : 0;
            line = line.copy().append(Component.literal(labels[ch.ordinal()] + c + " ").withStyle(c > 0 ? ch.color : ChatFormatting.DARK_GRAY));
            text.append(labels[ch.ordinal()]).append(c).append(' ');
        }
        g.drawString(font, line, right - font.width(text.toString()), y, 0xFFFFFF, false);
    }

    private void drawProgress(GuiGraphics g, int x, int y, int w) {
        long dur = state.durationMs();
        long posMs = state.positionMs();
        g.fill(x, y, x + w, y + 4, 0xFF0C0F14);
        if (dur > 0) {
            int filled = (int) (w * Mth.clamp(posMs / (double) dur, 0, 1));
            g.fill(x, y, x + filled, y + 4, ACCENT);
            g.drawString(font, time(posMs) + " / " + time(dur), x, y + 6, 0x6B7385, false);
        } else {
            g.fill(x, y, x + w, y + 4, 0xFFB03030);
            g.drawString(font, "● LIVE " + time(posMs), x, y + 6, 0xFF6060, false);
        }
    }

    private void drawMeters(GuiGraphics g, int x, int y) {
        drawMeter(g, x, y, "L", shownL, peakL, 0xFF3BA7FF);
        drawMeter(g, x, y + 10, "R", shownR, peakR, 0xFFFF4A4A);
        drawMeter(g, x, y + 20, "S", shownS, peakS, 0xFFFFB52E);
    }

    private void drawMeter(GuiGraphics g, int x, int y, String label, float level, float peak, int color) {
        g.drawString(font, label, x, y, 0x9AA4B8, false);
        int bx = x + 8;
        int segments = 16;
        for (int i = 0; i < segments; i++) {
            float t = i / (float) segments;
            int seg = t > 0.85f ? 0xFFFF3030 : t > 0.65f ? 0xFFFFD030 : color;
            boolean lit = level > t;
            boolean peakSeg = Math.abs(peak - t) < 1f / segments && peak > 0.05f;
            g.fill(bx + i * 4, y, bx + i * 4 + 3, y + 7, lit || peakSeg ? seg : 0xFF262C38);
        }
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    /** Horizontal fader bound to one {@link MixerParam}. */
    private final class Knob extends AbstractSliderButton {

        private final MixerParam param;
        private long lastTouched;

        Knob(int x, int y, int w, MixerParam param, float value) {
            super(x, y, w, 20, Component.empty(), param.toSlider(value));
            this.param = param;
            updateMessage();
        }

        float current() {
            return param.fromSlider(value);
        }

        void sync(float serverValue) {
            // don't fight the user while dragging / right after a change
            if (isFocused() && Util.getMillis() - lastTouched < 1_000) return;
            if (Util.getMillis() - lastTouched < 600) return;
            value = param.toSlider(serverValue);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("pvsound.param." + param.key)
                    .append(" ")
                    .append(Component.literal(param.format(current())).withStyle(ChatFormatting.YELLOW)));
        }

        @Override
        protected void applyValue() {
            lastTouched = Util.getMillis();
            float snapped = current();
            value = param.toSlider(snapped);
            PvSoundNetwork.CHANNEL.sendToServer(new PvSoundNetwork.MixerParamC2S(pos, param.ordinal(), snapped));
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // right-click resets the knob
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isMouseOver(mouseX, mouseY)) {
                value = param.toSlider(param.def);
                applyValue();
                updateMessage();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }
}
