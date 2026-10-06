package com.namo.pvsound.network;

import com.namo.pvsound.PvSound;
import com.namo.pvsound.block.MixerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class PvSoundNetwork {

    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            PvSound.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** Max distance (squared) a player may be from a mixer to control it. */
    private static final double REACH_SQ = 10 * 10;

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, MixerActionC2S.class, MixerActionC2S::write, MixerActionC2S::read,
                MixerActionC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MixerParamC2S.class, MixerParamC2S::write, MixerParamC2S::read,
                MixerParamC2S::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MixerStateS2C.class, MixerStateS2C::write, MixerStateS2C::read,
                MixerStateS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        SettingsNetwork.register(CHANNEL, id);
    }

    public static void sendState(ServerPlayer player, BlockPos pos, boolean open, MixerState state) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MixerStateS2C(pos, open, state));
    }

    private static MixerBlockEntity mixerFor(ServerPlayer player, BlockPos pos) {
        if (player == null || !player.level().isLoaded(pos)) return null;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > REACH_SQ) return null;
        return player.level().getBlockEntity(pos) instanceof MixerBlockEntity mixer ? mixer : null;
    }

    // ------------------------------------------------------------------ messages

    public enum Action { PLAY_NOW, ENQUEUE, PAUSE, SKIP, STOP, LOOP, CLEAR_QUEUE, CLOSE }

    public record MixerActionC2S(BlockPos pos, Action action, String arg) {
        static void write(MixerActionC2S msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.pos);
            buf.writeEnum(msg.action);
            buf.writeUtf(msg.arg, 1024);
        }

        static MixerActionC2S read(FriendlyByteBuf buf) {
            return new MixerActionC2S(buf.readBlockPos(), buf.readEnum(Action.class), buf.readUtf(1024));
        }

        static void handle(MixerActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                MixerBlockEntity mixer = mixerFor(player, msg.pos);
                if (mixer != null) mixer.handleAction(player, msg.action, msg.arg);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record MixerParamC2S(BlockPos pos, int param, float value) {
        static void write(MixerParamC2S msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.pos);
            buf.writeVarInt(msg.param);
            buf.writeFloat(msg.value);
        }

        static MixerParamC2S read(FriendlyByteBuf buf) {
            return new MixerParamC2S(buf.readBlockPos(), buf.readVarInt(), buf.readFloat());
        }

        static void handle(MixerParamC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                MixerBlockEntity mixer = mixerFor(ctx.get().getSender(), msg.pos);
                if (mixer != null) mixer.setParam(msg.param, msg.value);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record MixerStateS2C(BlockPos pos, boolean open, MixerState state) {
        static void write(MixerStateS2C msg, FriendlyByteBuf buf) {
            buf.writeBlockPos(msg.pos);
            buf.writeBoolean(msg.open);
            msg.state.write(buf);
        }

        static MixerStateS2C read(FriendlyByteBuf buf) {
            return new MixerStateS2C(buf.readBlockPos(), buf.readBoolean(), MixerState.read(buf));
        }

        static void handle(MixerStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.namo.pvsound.client.ClientHooks.onMixerState(msg.pos, msg.open, msg.state)));
            ctx.get().setPacketHandled(true);
        }
    }

    private PvSoundNetwork() {
    }
}
