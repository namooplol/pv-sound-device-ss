package com.namo.pvsound.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.namo.pvsound.audio.SpeakerChannel;
import com.namo.pvsound.block.SpeakerBlock;
import com.namo.pvsound.block.SpeakerBlockEntity;
import com.namo.pvsound.item.CableRules;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the cable from the back of a speaker through its cable clips to the mixer.
 * Each span sags like a real cable; spans between clips are short, so they hang less.
 */
public class SpeakerCableRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {

    private static final float THICKNESS = 0.03f;

    private record Sample(float x, float y, float z, int light) {
    }

    public SpeakerCableRenderer(BlockEntityRendererProvider.Context ctx) {
    }

    @Override
    public void render(SpeakerBlockEntity speaker, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockPos mixer = speaker.getMixerPos();
        Level level = speaker.getLevel();
        if (mixer == null || level == null) return;

        BlockState state = speaker.getBlockState();
        Direction back = state.hasProperty(SpeakerBlock.FACING) ? state.getValue(SpeakerBlock.FACING).getOpposite() : Direction.NORTH;
        SpeakerChannel channel = state.hasProperty(SpeakerBlock.CHANNEL) ? state.getValue(SpeakerBlock.CHANNEL) : SpeakerChannel.MONO;
        BlockPos origin = speaker.getBlockPos();

        // anchor points in world space: speaker back panel -> clips (reversed route) -> mixer back
        List<Vec3> points = new ArrayList<>();
        points.add(new Vec3(origin.getX() + 0.5 + back.getStepX() * 0.32, origin.getY() + 0.15, origin.getZ() + 0.5 + back.getStepZ() * 0.32));
        List<BlockPos> route = speaker.getRoute();
        for (int i = route.size() - 1; i >= 0; i--) points.add(CableRules.anchor(level, route.get(i)));
        points.add(new Vec3(mixer.getX() + 0.5, mixer.getY() + 0.2, mixer.getZ() + 0.5));

        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < points.size() - 1; s++) {
            sampleSpan(level, origin, points.get(s), points.get(s + 1), samples, s == 0);
        }

        int rgb = channel.rgb;
        float tr = ((rgb >> 16) & 0xFF) / 255f, tg = ((rgb >> 8) & 0xFF) / 255f, tb = (rgb & 0xFF) / 255f;
        float cr = 0.12f, cg = 0.12f, cb = 0.14f;

        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.leash());

        // vertical ribbon forward, horizontal ribbon backward (one strip, like vanilla leads)
        for (int i = 0; i < samples.size(); i++) {
            Sample p = samples.get(i);
            float shade = i % 2 == 0 ? 1f : 0.8f;
            boolean stripe = i % 6 == 3;
            float r = (stripe ? tr : cr) * shade, g = (stripe ? tg : cg) * shade, b = (stripe ? tb : cb) * shade;
            vc.vertex(m, p.x, p.y + THICKNESS, p.z).color(r, g, b, 1f).uv2(p.light).endVertex();
            vc.vertex(m, p.x, p.y - THICKNESS, p.z).color(r, g, b, 1f).uv2(p.light).endVertex();
        }
        for (int i = samples.size() - 1; i >= 0; i--) {
            Sample p = samples.get(i);
            Sample q = samples.get(i > 0 ? i - 1 : Math.min(1, samples.size() - 1));
            float dx = p.x - q.x, dz = p.z - q.z;
            float len = Mth.sqrt(dx * dx + dz * dz);
            float ox = len < 1.0e-4f ? THICKNESS : dz / len * THICKNESS;
            float oz = len < 1.0e-4f ? 0 : -dx / len * THICKNESS;
            float shade = i % 2 == 0 ? 0.85f : 0.7f;
            boolean stripe = i % 6 == 3;
            float r = (stripe ? tr : cr) * shade, g = (stripe ? tg : cg) * shade, b = (stripe ? tb : cb) * shade;
            vc.vertex(m, p.x - ox, p.y, p.z - oz).color(r, g, b, 1f).uv2(p.light).endVertex();
            vc.vertex(m, p.x + ox, p.y, p.z + oz).color(r, g, b, 1f).uv2(p.light).endVertex();
        }
    }

    /** Samples one sagging span between two anchors into speaker-local coordinates. */
    private static void sampleSpan(Level level, BlockPos origin, Vec3 a, Vec3 b, List<Sample> out, boolean includeStart) {
        double length = a.distanceTo(b);
        float sag = (float) Math.min(1.2, 0.08 * length + 0.05);
        int segments = Mth.clamp((int) (length * 2), 4, 24);

        int blockA = level.getBrightness(LightLayer.BLOCK, BlockPos.containing(a));
        int skyA = level.getBrightness(LightLayer.SKY, BlockPos.containing(a));
        int blockB = level.getBrightness(LightLayer.BLOCK, BlockPos.containing(b));
        int skyB = level.getBrightness(LightLayer.SKY, BlockPos.containing(b));

        for (int i = includeStart ? 0 : 1; i <= segments; i++) {
            float t = i / (float) segments;
            double x = Mth.lerp(t, a.x, b.x) - origin.getX();
            double y = Mth.lerp(t, a.y, b.y) - origin.getY() - sag * 4 * t * (1 - t);
            double z = Mth.lerp(t, a.z, b.z) - origin.getZ();
            int light = LightTexture.pack((int) Mth.lerp(t, blockA, blockB), (int) Mth.lerp(t, skyA, skyB));
            out.add(new Sample((float) x, (float) y, (float) z, light));
        }
    }

    @Override
    public boolean shouldRenderOffScreen(SpeakerBlockEntity speaker) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
