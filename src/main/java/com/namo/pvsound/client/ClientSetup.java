package com.namo.pvsound.client;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.item.CableRules;
import com.namo.pvsound.item.MicrophoneItem;
import com.namo.pvsound.item.SpeakerCableItem;
import com.namo.pvsound.registry.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class ClientSetup {

    @Mod.EventBusSubscriber(modid = PvSound.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ItemProperties.register(ModRegistry.MICROPHONE.get(), PvSound.id("on"),
                    (stack, level, entity, seed) -> MicrophoneItem.isOn(stack) ? 1f : 0f));
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(ModRegistry.SPEAKER_BE.get(), SpeakerCableRenderer::new);
        }
    }

    @Mod.EventBusSubscriber(modid = PvSound.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {

        private static final DustParticleOptions OK = new DustParticleOptions(new Vector3f(1f, 0.8f, 0.2f), 0.6f);
        private static final DustParticleOptions TOO_FAR = new DustParticleOptions(new Vector3f(1f, 0.15f, 0.15f), 0.6f);

        /** While holding a plugged cable, show a dotted line from the mixer to your hand (red when too long). */
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (event.phase != TickEvent.Phase.END || player == null || mc.level == null || mc.isPaused()) return;
            if (player.tickCount % 2 != 0) return;

            ItemStack stack = player.getMainHandItem().getItem() instanceof SpeakerCableItem
                    ? player.getMainHandItem() : player.getOffhandItem();
            if (!(stack.getItem() instanceof SpeakerCableItem)) return;

            GlobalPos mixer = SpeakerCableItem.getMixer(stack);
            if (mixer == null || !mixer.dimension().equals(mc.level.dimension())) return;

            List<Vec3> points = new ArrayList<>();
            points.add(Vec3.atCenterOf(mixer.pos()));
            for (BlockPos clip : SpeakerCableItem.getRoute(stack)) points.add(CableRules.anchor(mc.level, clip));
            Vec3 hand = player.position().add(0, 1.0, 0);
            if (points.get(points.size() - 1).distanceTo(hand) > 96) return;

            double length = CableRules.pathLength(mc.level, mixer.pos(), SpeakerCableItem.getRoute(stack), hand);
            DustParticleOptions dust = length > CableRules.maxLength(stack) ? TOO_FAR : OK;
            points.add(hand);
            for (int s = 0; s < points.size() - 1; s++) {
                Vec3 from = points.get(s);
                Vec3 to = points.get(s + 1);
                double span = from.distanceTo(to);
                int steps = (int) Math.max(2, span * 1.5);
                double sag = Math.min(1.5, 0.08 * span);
                for (int i = 0; i < steps; i++) {
                    double t = i / (double) steps;
                    Vec3 p = from.lerp(to, t).subtract(0, sag * 4 * t * (1 - t), 0);
                    mc.level.addParticle(dust, p.x, p.y, p.z, 0, 0, 0);
                }
            }
        }
    }

    private ClientSetup() {
    }
}
